package com.signalbox.jellyremote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;

/** Original geometric artwork for the fictional catalog used in screenshots. */
final class DemoArtwork {
    static Bitmap create(String id, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.scale(width / 600f, height / 900f);
        boolean ambient = id.startsWith("ambient-");
        if (ambient) id = id.substring("ambient-".length());
        boolean space = !id.startsWith("film");
        int variant = space ? 3 : Math.floorMod(id.hashCode() - "film0".hashCode(), 6);
        int[][] palettes = {
                {0xFF14273B, 0xFF655283, 0xFFE5B895},
                {0xFF103349, 0xFF318C9D, 0xFFA3E0DB},
                {0xFF342A37, 0xFFAE7359, 0xFFF3D6A3},
                {0xFF131D42, 0xFF4A5589, 0xFFD7BEDF},
                {0xFF193C43, 0xFF547F7A, 0xFFC0D5B1},
                {0xFF172840, 0xFF5771A5, 0xFFCCDFEA}
        };
        int[] palette = palettes[variant];
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 350, 900,
                new int[]{palette[0], palette[1], palette[2]}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 600, 900, paint);
        paint.setShader(null);
        paint.setColor(palette[2]);
        canvas.drawCircle(410, 235, space ? 105 : 74, paint);
        if (space) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            paint.setColor(0x77FFFFFF);
            canvas.save();
            canvas.rotate(-25, 410, 235);
            canvas.drawOval(235, 205, 585, 265, paint);
            canvas.restore();
            paint.setStyle(Paint.Style.FILL);
        }
        for (int i = 0; i < 56; i++) {
            paint.setColor((i % 3 == 0 ? 0xBB : 0x55) << 24 | 0xFFFFFF);
            canvas.drawCircle(25 + Math.floorMod(i * 137, 540), 40 + Math.floorMod(i * 73, 420), i % 3 == 0 ? 2f : 1f, paint);
        }
        for (int layer = 0; layer < 3; layer++) {
            Path mountain = new Path();
            float horizon = 465 + layer * 100;
            mountain.moveTo(-30, horizon + 65);
            mountain.cubicTo(85, horizon - 100, 180, horizon + 110, 280, horizon - 35);
            mountain.cubicTo(400, horizon - 155, 495, horizon + 55, 630, horizon - 80);
            mountain.lineTo(630, 920);
            mountain.lineTo(-30, 920);
            mountain.close();
            paint.setColor(layer == 0 ? 0x99303C55 : layer == 1 ? 0xDD1D2D40 : 0xFF102331);
            canvas.drawPath(mountain, paint);
        }
        if (ambient) return bitmap;
        paint.setColor(0xFFE4D6B9);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(13);
        canvas.drawText("J E L L Y R E M O T E   ·   D E M O   C O L L E C T I O N", 42, 68, paint);
        paint.setColor(Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextSize(48);
        String[][] titles = {{"THE NIGHT", "GARDEN"}, {"BEYOND", "THE BLUE"}, {"AMBER", "COAST"},
                {"QUIET", "ORBIT"}, {"AFTER", "THE RAIN"}, {"NORTHERN", "LIGHT"}};
        String[] title = space ? new String[]{"THE LITTLE", "ASTRONAUT"} : titles[variant];
        canvas.drawText(title[0], 42, 650, paint);
        canvas.drawText(title[1], 42, 710, paint);
        return bitmap;
    }
}
