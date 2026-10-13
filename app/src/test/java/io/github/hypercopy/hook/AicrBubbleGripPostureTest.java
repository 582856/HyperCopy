package io.github.hypercopy.hook;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AicrBubbleGripPostureTest {
    @Test public void leftHandSelectsLeftEdge() {
        assertTrue(AicrBubbleGrip.isLeftPosture(1f));
    }

    @Test public void rightHandSelectsRightEdge() {
        assertFalse(AicrBubbleGrip.isLeftPosture(2f));
    }

    @Test public void noHandUnknownOrInvalidPostureDefaultsToRight() {
        float[] postures = {-2f, -1f, 0f, 3f, 4f, 9f, 254f, 1.5f,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (float posture : postures) {
            assertFalse("posture=" + posture, AicrBubbleGrip.isLeftPosture(posture));
        }
    }

    @Test public void switchingHandsAndReleasingDoesNotRetainLeftEdge() {
        assertTrue(AicrBubbleGrip.isLeftPosture(1f));
        assertFalse(AicrBubbleGrip.isLeftPosture(2f));
        assertTrue(AicrBubbleGrip.isLeftPosture(1f));
        assertFalse(AicrBubbleGrip.isLeftPosture(-1f));
    }
}
