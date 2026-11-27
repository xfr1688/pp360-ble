package com.xfr1688.pp360;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * 拍拍360 的伪 3D 透视图。
 *
 * 纯 Canvas 绘制，不依赖任何图片资源：金色锥体 + 顶盘 18 个刻度 + 透明顶罩 + 提环。
 * 顶盘刻度随角度移动；连续旋转时整盘持续转动，当前朝向用冷蓝高亮。
 */
public class GimbalView extends View {

    private static final int GOLD_HI = 0xFFF6E3B0;
    private static final int GOLD    = 0xFFD8B25E;
    private static final int GOLD_LO = 0xFF8A6A28;
    private static final int BLUE    = 0xFF5AC8FA;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    private float angle = 0f;
    private boolean spinning = false;
    private boolean connected = false;
    private ValueAnimator anim;

    public GimbalView(Context c) {
        super(c);
    }

    public void setConnected(boolean b) {
        if (connected != b) {
            connected = b;
            invalidate();
        }
    }

    public void setSpinning(boolean b) {
        if (spinning == b) {
            return;
        }
        spinning = b;
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        if (b) {
            final float from = angle;
            anim = ValueAnimator.ofFloat(from, from + 360f);
            anim.setDuration(2600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator a) {
                    Object v = a.getAnimatedValue();
                    if (v instanceof Float) {
                        angle = (Float) v;
                        invalidate();
                    }
                }
            });
            anim.start();
        }
        invalidate();
    }

    /** 步进：平滑转到目标角度（可以是累加的多圈值） */
    public void setAngleTarget(float target) {
        if (spinning) {
            angle = target;
            invalidate();
            return;
        }
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        final float from = angle;
        anim = ValueAnimator.ofFloat(from, target);
        anim.setDuration(700);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                Object v = a.getAnimatedValue();
                if (v instanceof Float) {
                    angle = (Float) v;
                    invalidate();
                }
            }
        });
        anim.start();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        int W = getWidth();
        int H = getHeight();
        float cx = W / 2f;

        p.setStyle(Paint.Style.FILL);
        p.setShader(new RadialGradient(cx, H * 0.40f, W * 0.58f,
                new int[]{connected ? 0x33D8B25E : 0x14808088, 0x00000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, H * 0.40f, W * 0.58f, p);
        p.setShader(null);

        float baseY    = H * 0.84f;
        float platY    = H * 0.34f;
        float neckHalf = W * 0.060f;
        float plateR   = W * 0.150f;
        float baseR    = W * 0.245f;

        p.setColor(0x40000000);
        oval.set(cx - baseR * 1.20f, baseY - baseR * 0.14f,
                cx + baseR * 1.20f, baseY + baseR * 0.14f);
        c.drawOval(oval, p);

        p.setShader(new LinearGradient(cx - baseR, 0, cx + baseR, 0,
                new int[]{0xFF3A2C12, 0xFF6E5522, GOLD, 0xFF6E5522, 0xFF3A2C12},
                new float[]{0f, 0.22f, 0.5f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        oval.set(cx - baseR, baseY - baseR * 0.16f, cx + baseR, baseY + baseR * 0.16f);
        c.drawOval(oval, p);
        p.setShader(null);

        path.reset();
        path.moveTo(cx - neckHalf, platY + H * 0.055f);
        path.lineTo(cx + neckHalf, platY + H * 0.055f);
        path.lineTo(cx + baseR * 0.94f, baseY - H * 0.004f);
        path.lineTo(cx - baseR * 0.94f, baseY - H * 0.004f);
        path.close();
        p.setShader(new LinearGradient(cx - baseR, 0, cx + baseR, 0,
                new int[]{0xFF43320F, GOLD_LO, GOLD, GOLD_HI, GOLD_LO, 0xFF43320F},
                new float[]{0f, 0.16f, 0.40f, 0.54f, 0.80f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);

        path.reset();
        path.moveTo(cx - neckHalf * 0.9f, platY + H * 0.060f);
        path.lineTo(cx - neckHalf * 0.2f, platY + H * 0.060f);
        path.lineTo(cx - baseR * 0.34f, baseY - H * 0.006f);
        path.lineTo(cx - baseR * 0.62f, baseY - H * 0.006f);
        path.close();
        p.setShader(new LinearGradient(cx - baseR * 0.6f, platY, cx, baseY,
                new int[]{0x44FFFFFF, 0x00FFFFFF}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);

        p.setShader(new LinearGradient(cx - plateR, platY, cx + plateR, platY,
                new int[]{0xFF8A6A28, GOLD, GOLD_HI, GOLD, 0xFF8A6A28},
                new float[]{0f, 0.3f, 0.5f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        oval.set(cx - plateR, platY - plateR * 0.30f, cx + plateR, platY + plateR * 0.30f);
        c.drawOval(oval, p);
        p.setShader(null);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1f));
        p.setColor(0x55FFE9BE);
        c.drawOval(oval, p);
        p.setStyle(Paint.Style.FILL);

        float irx = plateR * 0.62f;
        float iry = irx * 0.30f;
        p.setColor(connected ? 0xFF101318 : 0xFF14161A);
        oval.set(cx - irx, platY - iry, cx + irx, platY + iry);
        c.drawOval(oval, p);

        float ar = (float) Math.toRadians(angle % 360f);
        float curX = cx + irx * (float) Math.cos(ar);
        float curY = platY + iry * (float) Math.sin(ar);
        for (int i = 0; i < 18; i++) {
            float th = (float) Math.toRadians(i * 20f + angle);
            float px = cx + irx * (float) Math.cos(th);
            float py = platY + iry * (float) Math.sin(th);
            boolean near = Math.abs(px - curX) < dp(1.5f) && Math.abs(py - curY) < dp(1.5f);
            p.setColor(near ? BLUE : 0x66D8B25E);
            c.drawCircle(px, py, near ? dp(3f) : dp(1.5f), p);
        }

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1.4f));
        p.setColor(0x805AC8FA);
        c.drawCircle(curX, curY, dp(5.5f), p);
        p.setStyle(Paint.Style.FILL);

        p.setColor(0x14FFFFFF);
        oval.set(cx - plateR * 0.96f, platY - plateR * 0.46f,
                cx + plateR * 0.96f, platY + plateR * 0.12f);
        c.drawOval(oval, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1f));
        p.setColor(0x33FFFFFF);
        c.drawOval(oval, p);
        p.setStyle(Paint.Style.FILL);

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(2.4f));
        p.setColor(connected ? 0xCCD8B25E : 0x88D8B25E);
        c.drawCircle(cx, platY - plateR * 0.62f, plateR * 0.16f, p);
        p.setStyle(Paint.Style.FILL);
    }
}
