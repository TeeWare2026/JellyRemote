package com.signalbox.jellyremote;

import android.graphics.Bitmap;
import android.graphics.Color;

final class ThemeColors {
    final int primary;
    final int secondary;
    final int deep;

    ThemeColors(int primary, int secondary, int deep) {
        this.primary = primary;
        this.secondary = secondary;
        this.deep = deep;
    }

    static ThemeColors defaults() {
        return new ThemeColors(Color.rgb(113, 225, 244), Color.rgb(162, 127, 241), Color.rgb(7, 16, 24));
    }

    static ThemeColors from(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return defaults();
        float[] hueScores = new float[36];
        float[] satSums = new float[36];
        float[] valSums = new float[36];
        float[] hsv = new float[3];
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int stepX = Math.max(1, width / 48);
        int stepY = Math.max(1, height / 48);
        double red = 0, green = 0, blue = 0, total = 0;
        float chromaTotal = 0f;

        for (int y = stepY / 2; y < height; y += stepY) {
            for (int x = stepX / 2; x < width; x += stepX) {
                int color = bitmap.getPixel(x, y);
                Color.colorToHSV(color, hsv);
                float weight = (.08f + hsv[1] * hsv[1] * 2.4f) * (.3f + hsv[2]);
                int bucket = Math.min(35, (int) (hsv[0] / 10f));
                if (hsv[2] > .12f) {
                    hueScores[bucket] += weight;
                    satSums[bucket] += hsv[1] * weight;
                    valSums[bucket] += hsv[2] * weight;
                    chromaTotal += hsv[1] * weight;
                }
                float averageWeight = .25f + hsv[2] * .75f;
                red += Color.red(color) * averageWeight;
                green += Color.green(color) * averageWeight;
                blue += Color.blue(color) * averageWeight;
                total += averageWeight;
            }
        }

        if (total == 0) return defaults();
        int average = Color.rgb((int) (red / total), (int) (green / total), (int) (blue / total));
        int first = bestBucket(hueScores, -1);
        boolean nearlyNeutral = chromaTotal / Math.max(1f, (float) total) < .07f;
        int primary;
        int secondary;
        if (nearlyNeutral) {
            primary = Color.rgb(190, 216, 226);
            secondary = Color.rgb(178, 168, 211);
        } else {
            int second = bestBucket(hueScores, first);
            primary = accent(first, hueScores, satSums, valSums);
            if (second < 0 || hueScores[second] < hueScores[first] * .08f) {
                float[] primaryHsv = new float[3];
                Color.colorToHSV(primary, primaryHsv);
                primaryHsv[0] = (primaryHsv[0] + (primaryHsv[0] > 245f ? -42f : 42f) + 360f) % 360f;
                primaryHsv[1] = Math.max(.42f, primaryHsv[1] * .82f);
                secondary = Color.HSVToColor(primaryHsv);
            } else {
                secondary = accent(second, hueScores, satSums, valSums);
            }
        }
        float[] averageHsv = new float[3];
        Color.colorToHSV(average, averageHsv);
        averageHsv[1] = Math.min(.55f, Math.max(.18f, averageHsv[1]));
        averageHsv[2] = .105f;
        int deepTint = Color.HSVToColor(averageHsv);
        int deep = blend(Color.rgb(3, 8, 13), deepTint, .72f);
        return new ThemeColors(primary, secondary, deep);
    }

    private static int bestBucket(float[] scores, int excluded) {
        int best = -1;
        float bestScore = 0f;
        for (int i = 0; i < scores.length; i++) {
            if (excluded >= 0) {
                int distance = Math.abs(i - excluded);
                distance = Math.min(distance, scores.length - distance);
                if (distance < 4) continue;
            }
            float separation = excluded < 0 ? 1f : Math.min(1.35f, .72f + Math.abs(i - excluded) / 18f);
            float score = scores[i] * separation;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best < 0 && excluded < 0 ? 19 : best;
    }

    private static int accent(int bucket, float[] scores, float[] sats, float[] values) {
        if (bucket < 0) return Color.rgb(113, 225, 244);
        float divisor = Math.max(.001f, scores[bucket]);
        float saturation = Math.max(.48f, Math.min(.80f, sats[bucket] / divisor + .12f));
        float value = Math.max(.82f, Math.min(.98f, values[bucket] / divisor + .22f));
        return Color.HSVToColor(new float[]{bucket * 10f + 5f, saturation, value});
    }

    static int blend(int from, int to, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(
                (int) (Color.red(from) * inverse + Color.red(to) * amount),
                (int) (Color.green(from) * inverse + Color.green(to) * amount),
                (int) (Color.blue(from) * inverse + Color.blue(to) * amount));
    }
}
