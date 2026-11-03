package com.termux.terminal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminalAnsiStyleTest {

    @Test public void indexedForegroundUsesSafeSgr() {
        long style = TextStyle.encode(209, TextStyle.COLOR_INDEX_BACKGROUND, 0);
        assertEquals("\u001b[0;38;5;209m", TerminalAnsiStyle.toSgr(style));
    }

    @Test public void trueColorAndTextEffectsArePreserved() {
        int effects = TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
        long style = TextStyle.encode(0xff12ab34, 0xff010203, effects);
        assertEquals("\u001b[0;38;2;18;171;52;48;2;1;2;3;1;4m", TerminalAnsiStyle.toSgr(style));
    }

    @Test public void defaultStyleResetsPriorColorAndEffects() {
        assertEquals("\u001b[0m", TerminalAnsiStyle.toSgr(TextStyle.NORMAL));
    }
}
