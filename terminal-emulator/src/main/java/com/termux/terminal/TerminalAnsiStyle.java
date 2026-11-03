package com.termux.terminal;

import java.util.ArrayList;
import java.util.List;

/** Converts a cell's stored visual attributes into a safe SGR-only sequence. */
public final class TerminalAnsiStyle {

    private TerminalAnsiStyle() {}

    public static String toSgr(long style) {
        if (TextStyle.isTerminalBitmap(style)) return "\u001b[0m";

        List<Integer> params = new ArrayList<>();
        int foreground = TextStyle.decodeForeColor(style);
        int background = TextStyle.decodeBackColor(style);
        appendColor(params, foreground, 38, TextStyle.COLOR_INDEX_FOREGROUND);
        appendColor(params, background, 48, TextStyle.COLOR_INDEX_BACKGROUND);

        int effects = TextStyle.decodeEffect(style);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0) params.add(1);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) params.add(2);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0) params.add(3);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0) params.add(4);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0) params.add(5);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0) params.add(7);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0) params.add(8);
        if ((effects & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0) params.add(9);

        StringBuilder sequence = new StringBuilder("\u001b[0");
        for (int param : params) sequence.append(';').append(param);
        return sequence.append('m').toString();
    }

    private static void appendColor(List<Integer> params, int color, int base, int defaultIndex) {
        if (color == defaultIndex || color == TextStyle.COLOR_INDEX_CURSOR) return;
        if ((color & 0xff000000) == 0xff000000) {
            params.add(base);
            params.add(2);
            params.add((color >>> 16) & 0xff);
            params.add((color >>> 8) & 0xff);
            params.add(color & 0xff);
        } else if (color >= 0 && color <= 255) {
            params.add(base);
            params.add(5);
            params.add(color);
        }
    }
}
