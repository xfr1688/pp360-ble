package com.xfr1688.pp360;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Camera1 极简封装：预览 + 拍照 + 人脸坐标回调。不引入 AndroidX，保持工程零依赖。 */
class CameraController implements TextureView.SurfaceTextureListener {

    interface Cb {
        void camLog(String m);

        void camReady(int w, int h);

        void camError(String m);

        void camShot(String path);

        void camFace(float nx, float ny);
    }

    private final Context ctx;
    private final Cb cb;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Camera cam;
    private TextureView tv;
    private int pw = 640;
    private int ph = 480;
    private boolean faceOn = false;
    private boolean shooting = false;
    private long lastFaceMs = 0;

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

    boolean faceAvailable() {
        if (cam == null) {
            return false;
        }
        try {
            return cam.getParameters().getMaxNumDetectedFaces() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    void faceOn(boolean on) {
        if (cam == null) {
            return;
        }
        try {
            if (on && !faceOn) {
                if (cam.getParameters().getMaxNumDetectedFaces() <= 0) {
                    cb.camLog("本机摄像头不支持人脸检测");
                    return;
                }
                cam.setFaceDetectionListener(faceListener);
                cam.startFaceDetection();
                faceOn = true;
                cb.camLog("人脸检测已开启");
            } else if (!on && faceOn) {
                try {
                    cam.stopFaceDetection();
                } catch (Exception ignored) {
                }
                try {
                    cam.setFaceDetectionListener(null);
                } catch (Exception ignored) {
                }
                faceOn = false;
            }
        } catch (Exception e) {
            cb.camLog("人脸检测开关失败：" + e.getClass().getSimpleName());
        }
    }

    private final Camera.FaceDetectionListener faceListener = new Camera.FaceDetectionListener() {
        @Override
        public void onFaceDetection(Camera.Face[] faces, Camera camera) {
            if (faces == null || faces.length == 0) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastFaceMs < 110) {
                return;
            }
            lastFaceMs = now;
            Camera.Face best = faces[0];
            for (int i = 1; i < faces.length; i++) {
                if (faces[i].score > best.score) {
                    best = faces[i];
                }
            }
            int cx = best.rect.centerX();
            int cy = best.rect.centerY();
            final float nx;
            final float ny;
            if (cx > -1000 && cx < 1000 && cy > -1000 && cy < 1000 && pw > 1000) {
                nx = cx / 2000f;
                ny = cy / 2000f;
            } else {
                nx = (cx - pw / 2f) / pw;
                ny = (cy - ph / 2f) / ph;
            }
            ui.post(new Runnable() {
                @Override
                public void run() {
                    cb.camFace(nx, ny);
                }
            });
        }
    };

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
            List<String> fm = p.getSupportedFocusModes();
            if (fm != null) {
                if (fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                }
            }
            c.setParameters(p);
            c.setDisplayOrientation(90);
            c.setPreviewTexture(tv.getSurfaceTexture());
            c.startPreview();
            cam = c;
            final int fw = pw;
            final int fh = ph;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    cb.camReady(fw, fh);
                }
            });
            cb.camLog("相机就绪 " + pw + "x" + ph);
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
            if (faceOn) {
                cam.stopFaceDetection();
            }
        } catch (Exception ignored) {
        }
        try {
            cam.setFaceDetectionListener(null);
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
        faceOn = false;
        shooting = false;
    }

    void shoot(final String tag) {
        if (cam == null) {
            cb.camError("相机未就绪");
            return;
        }
        if (shooting) {
            return;
        }
        shooting = true;
        try {
            Camera.Parameters p = cam.getParameters();
            p.setRotation(90);
            cam.setParameters(p);
        } catch (Exception ignored) {
        }
        try {
            cam.takePicture(null, null, new Camera.PictureCallback() {
                @Override
                public void onPictureTaken(byte[] data, Camera camera) {
                    final String path = save(data, tag);
                    shooting = false;
                    try {
                        camera.startPreview();
                        if (faceOn) {
                            camera.startFaceDetection();
                        }
                    } catch (Exception ignored) {
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
