package io.github.hypercopy.hook;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.animation.AccelerateDecelerateInterpolator;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import io.github.hypercopy.Config;

/** Moves only copy-direct children; the native bubble keeps its animations and click handler. */
final class AicrBubbleGrip implements SensorEventListener,
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private final ViewGroup container;
    private final Context context;
    private final SharedPreferences preferences;
    private final Runnable onDisposed;
    private final Consumer<String> logger;
    private final SensorManager sensors;
    private final Sensor grip;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<View> bubbles = new HashSet<>();
    private final Runnable initialSideTimeout = this::finishInitialSideWait;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (prefs, key) -> {
        if (key == null || Config.KEY_COPY_DIRECT_ADAPTIVE_GRIP.equals(key)) {
            mainHandler.post(this::refreshSetting);
        }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            updateListening();
        }
    };
    private boolean enabled;
    private boolean listening;
    private boolean screenOn;
    private boolean receiverRegistered;
    private boolean sensorUnavailable;
    private boolean disposed;
    private boolean hasDrawn;
    private boolean initialSideReady;
    private long initialSideDeadline;
    private boolean left;
    private int edgeMargin;
    private float leftFraction;
    private ValueAnimator animator;

    AicrBubbleGrip(ViewGroup container, SharedPreferences preferences, Runnable onDisposed,
            Consumer<String> logger) {
        this.container = container;
        this.context = container.getContext();
        this.preferences = preferences;
        this.onDisposed = onDisposed;
        this.logger = logger;
        sensors = context.getSystemService(SensorManager.class);
        grip = sensors == null ? null : sensors.getDefaultSensor(33171120);
        PowerManager power = context.getSystemService(PowerManager.class);
        screenOn = power == null || power.isInteractive();
        container.addOnAttachStateChangeListener(this);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        refreshSetting();
    }

    boolean isDisposed() {
        return disposed;
    }

    void onLayout(List<View> copyBubbles, int edgeMargin) {
        if (disposed) return;
        this.edgeMargin = edgeMargin;
        for (View bubble : new HashSet<>(bubbles)) {
            if (!copyBubbles.contains(bubble)) {
                bubble.removeOnAttachStateChangeListener(this);
                bubbles.remove(bubble);
            }
        }
        for (View bubble : copyBubbles) {
            if (bubbles.add(bubble)) bubble.addOnAttachStateChangeListener(this);
        }
        if (bubbles.isEmpty()) {
            dispose();
            return;
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
        }
        // Native enter/exit listeners set translationX during pre-draw. Run after those listeners.
        ViewTreeObserver observer = container.getViewTreeObserver();
        observer.removeOnPreDrawListener(this);
        observer.addOnPreDrawListener(this);
        updateListening();
        positionBubbles();
    }

    private void refreshSetting() {
        if (disposed) return;
        enabled = preferences.getBoolean(
            Config.KEY_COPY_DIRECT_ADAPTIVE_GRIP, Config.DEFAULT_COPY_DIRECT_ADAPTIVE_GRIP);
        if (!enabled) {
            left = false;
            animateTo(false);
        }
        updateListening();
    }

    private boolean hasVisibleBubble() {
        if (!screenOn || container.getWindowVisibility() != View.VISIBLE || !container.isShown()) return false;
        for (View bubble : bubbles) {
            if (bubble.isAttachedToWindow() && bubble.isShown()) return true;
        }
        return false;
    }

    private void updateListening() {
        boolean shouldListen = !disposed && enabled && !sensorUnavailable && grip != null && hasVisibleBubble();
        if (shouldListen == listening) return;
        if (shouldListen) {
            try {
                listening = sensors.registerListener(this, grip, SensorManager.SENSOR_DELAY_UI, mainHandler);
            } catch (RuntimeException ignored) {
                listening = false;
            }
            sensorUnavailable = !listening;
            logger.accept("AICR adaptive grip GPD registered=" + listening);
            if (listening && !hasDrawn) {
                initialSideReady = false;
                initialSideDeadline = SystemClock.uptimeMillis() + 150L;
                mainHandler.postDelayed(initialSideTimeout, 150L);
            }
        } else {
            sensors.unregisterListener(this);
            listening = false;
            left = false;
            mainHandler.removeCallbacks(initialSideTimeout);
            logger.accept("AICR adaptive grip GPD stopped");
        }
        if (!hasVisibleBubble()) {
            if (animator != null) animator.cancel();
            leftFraction = 0f;
            hasDrawn = false;
            initialSideReady = false;
        }
    }

    @Override public boolean onPreDraw() {
        updateListening();
        // The first sensor sample chooses the entry edge; never render a provisional right side.
        // Bound the wait so missing sensor data cannot prevent the system bubble from appearing.
        if (listening && !hasDrawn && !initialSideReady
                && SystemClock.uptimeMillis() < initialSideDeadline) return false;
        initialSideReady = true;
        positionBubbles();
        hasDrawn = true;
        return true;
    }

    private void finishInitialSideWait() {
        if (!disposed && !initialSideReady) {
            initialSideReady = true;
            container.invalidate();
        }
    }

    private void positionBubbles() {
        Insets insets = Insets.NONE;
        WindowInsets windowInsets = container.getRootWindowInsets();
        if (windowInsets != null) {
            insets = windowInsets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        }
        for (View bubble : bubbles) {
            int right = container.getWidth() - insets.right - edgeMargin - bubble.getWidth();
            int left = Math.min(right, insets.left + edgeMargin);
            // Animate actual bounds, not translationX owned by native Folme. Reflect only its
            // entry/exit offset on the left; text, icons and Android's hit testing stay unmirrored.
            int x = horizontalPosition(left, right, leftFraction, bubble.getTranslationX());
            bubble.offsetLeftAndRight(x - bubble.getLeft());
        }
    }

    static int horizontalPosition(int left, int right, float leftFraction, float nativeTranslation) {
        return Math.round(right + (left - right) * leftFraction - 2f * leftFraction * nativeTranslation);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!listening || disposed) return;
        if (!hasVisibleBubble()) {
            updateListening();
            return;
        }
        if (event.sensor.getType() != 33171120 || event.values.length == 0) return;
        boolean isLeft = isLeftPosture(event.values[0]);
        if (!hasDrawn && !initialSideReady) {
            // GPD is on-change: its initial cached event is the latest known posture.
            // Waiting for a second event could leave a steady left-hand grip on the right.
            initialSideReady = true;
            mainHandler.removeCallbacks(initialSideTimeout);
            left = isLeft;
            leftFraction = left ? 1f : 0f;
            logger.accept("AICR adaptive grip GPD initial posture=" + event.values[0]);
            positionBubbles();
            container.invalidate();
            return;
        }
        if (left != isLeft) {
            left = isLeft;
            logger.accept("AICR adaptive grip GPD posture=" + event.values[0]);
            animateTo(left);
        }
    }

    static boolean isLeftPosture(float posture) {
        return posture == 1f;
    }

    private void animateTo(boolean left) {
        float target = left ? 1f : 0f;
        if (animator != null) animator.cancel();
        if (leftFraction == target) return;
        animator = ValueAnimator.ofFloat(leftFraction, target);
        animator.setDuration(280L);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            leftFraction = (float) animation.getAnimatedValue();
            positionBubbles();
        });
        animator.start();
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override public void onViewAttachedToWindow(View view) {
        updateListening();
    }

    @Override public void onViewDetachedFromWindow(View view) {
        if (view == container) {
            dispose();
        } else {
            bubbles.remove(view);
            view.removeOnAttachStateChangeListener(this);
            if (bubbles.isEmpty()) dispose(); else updateListening();
        }
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        if (sensors != null) sensors.unregisterListener(this);
        if (listening) logger.accept("AICR adaptive grip GPD stopped");
        listening = false;
        mainHandler.removeCallbacks(initialSideTimeout);
        if (animator != null) animator.cancel();
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        if (receiverRegistered) context.unregisterReceiver(screenReceiver);
        container.removeOnAttachStateChangeListener(this);
        ViewTreeObserver observer = container.getViewTreeObserver();
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        for (View bubble : bubbles) bubble.removeOnAttachStateChangeListener(this);
        bubbles.clear();
        onDisposed.run();
    }
}
