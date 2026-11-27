package com.xfr1688.pp360;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.FaceDetector;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Camera1 + TextureView 封装。
 *
 * 人脸检测不再依赖相机 HAL 的 FaceDetection（国产 ROM 经常不实现，这是上一版“抓拍不能用”的原因），
 * 改用 android.media.FaceDetector 在预览帧上做软件检测，只要有帧就能跑。
 * 拍照前先 autoFocus 并对焦超时做兜底，避免“拍得糊”。
 */
class CameraController implements TextureView.SurfaceTextureListener {

    interface Cb {
        void camLog(String m);

        void camReady(int w, int h);

        void camError(String m);

        void camShot(String path);

        /** nx、ny 归一化到 -0.5~0.5，size 为眼距占检测宽度的百分比 */
        void camFace(float nx, float ny, int size);
    }

    private static final int DET_W = 160;
    private static final int DET_H = 120;

    private final Context ctx;
    private final Cb cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    private Camera cam;
    private TextureView tv;
    private int pw = 640;
    private int ph = 480;
    private boolean faceMode = false;
    private boolean shooting = false;
    private boolean takeDone = false;
    private long lastDetect = 0;
    private volatile boolean detecting = false;

    CameraController(Context ctx, Cb cb) {
        this.ctx = ctx;
        this.cb = cb;
    }

    void attach(TextureView v) {
        tv = v;
        v.setSurfaceTextureListener(this);
        if (v.isAvailable()) {
            open();
        }
    }

    void detach() {
        close();
        if (tv != null) {
            tv.setSurfaceTextureListener(null);
        }
        tv = null;
        pool.shutdownNow();
    }

    boolean isOpen() {
        return cam != null;
    }

    int width() {
        return pw;
    }

    int height() {
        return ph;
    }

    /** 软件检测总是可用 */
    boolean faceAvailable() {
        return cam != null;
    }

    synchronized void faceOn(boolean on) {
        faceMode = on;
        if (cam == null) {
            return;
        }
        try {
            if (on) {
                cam.setPreviewCallback(previewCb);
                cb.camLog("人脸检测开启（软件检测 " + DET_W + "×" + DET_H + "）");
            } else {
                cam.setPreviewCallback(null);
            }
        } catch (Exception e) {
            cb.camLog("人脸检测开关失败：" + e.getClass().getSimpleName());
        }
    }

    /* ====================== 预览帧 → 检测 ====================== */

    private final Camera.PreviewCallback previewCb = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(final byte[] data, Camera camera) {
            if (!faceMode || detecting) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastDetect < 260) {
                return;
            }
            lastDetect = now;
            detecting = true;
            final int w = pw;
            final int h = ph;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        detect(data, w, h);
                    } catch (Throwable ignored) {
                    } finally {
                        detecting = false;
                    }
                }
            });
        }
    };

    private void detect(byte[] nv21, int w, int h) {
        if (nv21 == null) {
            return;
        }
        Bitmap bmp = nv21To565(nv21, w, h);
        if (bmp == null) {
            return;
        }
        FaceDetector fd = new FaceDetector(DET_W, DET_H, 1);
        FaceDetector.Face[] faces = new FaceDetector.Face[1];
        int n = fd.findFaces(bmp, faces);
        if (n <= 0 || faces[0] == null) {
            return;
        }
        PointF mid = new PointF();
        try {
            faces[0].getMidPoint(mid);
        } catch (Exception e) {
            return;
        }
        float conf = 0;
        try {
            conf = faces[0].confidence();
        } catch (Exception ignored) {
        }
        if (conf < 0.3f) {
            return;
        }
        float eye = 0;
        try {
            eye = faces[0].eyesDistance();
        } catch (Exception ignored) {
        }
        final float nx = (mid.x - DET_W / 2f) / DET_W;
        final float ny = (mid.y - DET_H / 2f) / DET_H;
        final int size = Math.round(eye / DET_W * 100f);
        ui.post(new Runnable() {
            @Override
            public void run() {
                cb.camFace(nx, ny, size);
            }
        });
    }

    /** NV21 隔行取样转 RGB565，尺寸降到 160×120 */
    private Bitmap nv21To565(byte[] d, int sw, int sh) {
        int[] px = new int[DET_W * DET_H];
        int frameSize = sw * sh;
        for (int y = 0; y < DET_H; y++) {
            int sy = y * sh / DET_H;
            int rowY = sy * sw;
            int uvRow = frameSize + (sy >> 1) * sw;
            for (int x = 0; x < DET_W; x++) {
                int sx = x * sw / DET_W;
                int idx = rowY + sx;
                if (idx >= frameSize) {
                    break;
                }
                int yy = d[idx] & 0xFF;
                int uvi = uvRow + (sx >> 1) * 2;
                int vv = 0;
                int uu = 0;
                if (uvi + 1 < d.length) {
                    vv = (d[uvi] & 0xFF) - 128;
                    uu = (d[uvi + 1] & 0xFF) - 128;
                }
                int r = yy + (int) (1.402f * vv);
                int g = yy - (int) (0.344f * uu + 0.714f * vv);
                int b = yy + (int) (1.772f * uu);
                r = r < 0 ? 0 : (r > 255 ? 255 : r);
                g = g < 0 ? 0 : (g > 255 ? 255 : g);
                b = b < 0 ? 0 : (b > 255 ? 255 : b);
                px[y * DET_W + x] = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
            }
        }
        try {
            return Bitmap.createBitmap(px, DET_W, DET_H, Bitmap.Config.RGB_565);
        } catch (Exception e) {
            return null;
        }
    }

    /* ====================== 开关相机 ====================== */

    synchronized void open() {
        if (cam != null || tv == null || !tv.isAvailable()) {
            return;
        }
        Camera c;
        try {
            c = Camera.open(0);
        } catch (Exception e) {
            cb.camError("相机打不开：" + e.getClass().getSimpleName());
            return;
        }
        try {
            Camera.Parameters p = c.getParameters();
            Camera.Size best = null;
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            if (sizes != null) {
                for (int i = 0; i < sizes.size(); i++) {
                    Camera.Size s = sizes.get(i);
                    if ((long) s.width * s.height > 1280L * 720L) {
                        continue;
                    }
                    if (best == null || (long) s.width * s.height > (long) best.width * best.height) {
                        best = s;
                    }
                }
            }
            if (best != null) {
                p.setPreviewSize(best.width, best.height);
                pw = best.width;
                ph = best.height;
            }
            p.setPreviewFormat(ImageFormat.NV21);
            p.setJpegQuality(92);
            List<String> fm = p.getSupportedFocusModes();
            if (fm != null) {
                if (fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (fm.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
            c.setParameters(p);
            c.setDisplayOrientation(90);
            c.setPreviewTexture(tv.getSurfaceTexture());
            c.startPreview();
            cam = c;
            if (faceMode) {
                try {
                    c.setPreviewCallback(previewCb);
                } catch (Exception ignored) {
                }
            }
            final int fw = pw;
            final int fh = ph;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    cb.camReady(fw, fh);
                }
            });
            cb.camLog("相机就绪 " + pw + "×" + ph);
        } catch (Exception e) {
            try {
                c.release();
            } catch (Exception ignored) {
            }
            cb.camError("相机初始化失败：" + e.getMessage());
        }
    }

    synchronized void close() {
        if (cam == null) {
            return;
        }
        try {
            cam.setPreviewCallback(null);
        } catch (Exception ignored) {
        }
        try {
            cam.stopPreview();
        } catch (Exception ignored) {
        }
        try {
            cam.release();
        } catch (Exception ignored) {
        }
        cam = null;
        faceMode = false;
        shooting = false;
        takeDone = false;
    }

    /* ====================== 拍照 ====================== */

    void shoot(final String tag) {
        if (cam == null) {
            cb.camError("相机未就绪");
            return;
        }
        if (shooting) {
            return;
        }
        shooting = true;
        takeDone = false;
        final Camera c = cam;
        final Runnable take = new Runnable() {
            @Override
            public void run() {
                if (takeDone) {
                    return;
                }
                takeDone = true;
                doTake(c, tag);
            }
        };
        boolean focused = false;
        try {
            c.autoFocus(new Camera.AutoFocusCallback() {
                @Override
                public void onAutoFocus(boolean success, Camera camera) {
                    ui.post(take);
                }
            });
            focused = true;
        } catch (Exception e) {
            focused = false;
        }
        if (focused) {
            ui.postDelayed(take, 900);
        } else {
            ui.post(take);
        }
    }

    private void doTake(final Camera c, final String tag) {
        try {
            Camera.Parameters p = c.getParameters();
            p.setRotation(90);
            p.setJpegQuality(92);
            c.setParameters(p);
        } catch (Exception ignored) {
        }
        try {
            c.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    final String path = save(data, tag);
                    shooting = false;
                    try {
                        camera.startPreview();
                    } catch (Exception ignored) {
                    }
                    if (faceMode) {
                        try {
                            camera.setPreviewCallback(previewCb);
                        } catch (Exception ignored) {
                        }
                    }
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            if (path == null) {
                                cb.camError("照片写入失败");
                            } else {
                                cb.camShot(path);
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            shooting = false;
            cb.camError("拍照失败：" + e.getClass().getSimpleName());
        }
    }

    private String save(byte[] data, String tag) {
        try {
            File dir = ctx.getExternalFilesDir("shots");
            if (dir == null) {
                dir = new File(ctx.getFilesDir(), "shots");
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            File f = new File(dir, tag + "_" + ts + ".jpg");
            FileOutputStream o = new FileOutputStream(f);
            o.write(data);
            o.close();
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    /* ====================== TextureView 回调 ====================== */

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        open();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        close();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }
}
