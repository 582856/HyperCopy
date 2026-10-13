package io.github.hypercopy.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AicrBubbleGripPositionTest {
    @Test public void stationaryBubbleUsesEitherEdgeAndInterpolatesMidpoint() {
        assertEquals(800, AicrBubbleGrip.horizontalPosition(20, 800, 0f, 0f));
        assertEquals(20, AicrBubbleGrip.horizontalPosition(20, 800, 1f, 0f));
        assertEquals(410, AicrBubbleGrip.horizontalPosition(20, 800, 0.5f, 0f));
    }

    @Test public void nativeEntryAndExitOffsetIsReflectedWithoutChangingTranslation() {
        int nativeTranslation = 200;
        assertEquals(1000, AicrBubbleGrip.horizontalPosition(20, 800, 0f, nativeTranslation) + nativeTranslation);
        assertEquals(-180, AicrBubbleGrip.horizontalPosition(20, 800, 1f, nativeTranslation) + nativeTranslation);
        assertEquals(410, AicrBubbleGrip.horizontalPosition(20, 800, 0.5f, nativeTranslation) + nativeTranslation);
    }

    @Test public void switchingBackOrRepeatedLayoutDoesNotAccumulateOffset() {
        for (int i = 0; i < 100; i++) {
            assertEquals(20, AicrBubbleGrip.horizontalPosition(20, 800, 1f, 0f));
            assertEquals(800, AicrBubbleGrip.horizontalPosition(20, 800, 0f, 0f));
        }
    }
}
