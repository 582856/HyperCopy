package io.github.hypercopy.hook;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ClipboardManager;
import android.app.Activity;
import android.app.Application;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONObject;

import io.github.hypercopy.Config;
import io.github.libxposed.api.XposedModule;

public class HookEntry extends XposedModule {
    private static final String TAG = "HyperCopy";
    private static final String CLIPBOARD_SERVICE_CLASS = "com.android.server.clipboard.ClipboardService";
    private static final long DUPLICATE_WINDOW_MILLIS = 1500L;
    private static final int INSTALL_RETRY_LIMIT = 20;
    private static final long INSTALL_RETRY_DELAY_MILLIS = 1000L;
    private static final int CLEAR_RECEIVER_RETRY_LIMIT = 30;
    private static final long CLEAR_RECEIVER_RETRY_DELAY_MILLIS = 1000L;
    private static final String AICR_PACKAGE = "com.xiaomi.aicr";
    private static final String AICR_PROCESS = "com.xiaomi.aicr:cognitionService";
    private static final String AICR_BUBBLE_MANAGER = "rm0";
    private static final String AICR_CLICK_RECORDER = "ig8";
    private static final String AICR_BUBBLE_CONTAINER =
        "com.xiaomi.ai.bubble.core.bubbleview.view.BubbleContainerView";
    private static final String AICR_CUE_DATA = "com.xiaomi.ai.bubble.core.model.CueData";
    private static final String AICR_CUE_ID_PREFIX = "hypercopy.copy_jump#";
    private static final String SYSTEM_THEME_FONT_PATH = "/data/system/theme/fonts/Miui-Regular.ttf";
    private static final long AICR_CLICK_WINDOW_MILLIS = 4_000L;
    private static final long AICR_TARGET_WINDOW_MILLIS = 10_000L;
    private static final long AICR_INJECT_DELAY_MILLIS = 300L;

    private static String lastText = "";
    private static long lastSentAt = 0L;
    private static boolean lastSentHandled = false;
    private boolean hooksInstalled = false;
    private boolean aicrAttachHookInstalled = false;
    private boolean aicrHooksInstalled = false;
    private Context aicrContext;
    private volatile Object aicrBubbleManager;
    private Typeface systemThemeTypeface;
    private volatile String lastAicrClipboardText = "";
    private volatile PendingAicrTarget pendingAicrTarget;
    private volatile float pendingAicrClickY = -1f;
    private volatile long pendingAicrClickAt = 0L;
    private volatile float lastAicrClickY = -1f;
    private volatile long lastAicrClickAt = 0L;
    private final ConcurrentHashMap<String, PendingAicrTarget> aicrCueTargets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Float> aicrCuePositions = new ConcurrentHashMap<>();
    private final Set<String> aicrCopyCueIds = ConcurrentHashMap.newKeySet();
    private boolean clearReceiverRegistered = false;

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        HookLog.init(this);
        logDebug("module loaded: process=" + param.getProcessName()
            + ", systemServer=" + param.isSystemServer()
            + ", api=" + getApiVersion());
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        installClipboardHooksWithRetry(param.getClassLoader(), "onSystemServerStarting", 0);
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!AICR_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) return;
        installAicrAttachHook();
    }

    private void installAicrAttachHook() {
        if (aicrAttachHookInstalled) return;
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            attach.setAccessible(true);
            hook(attach).setId("hypercopy_aicr_attach").intercept(chain -> {
                Object context = chain.getArg(0);
                Object result = chain.proceed();
                if (!AICR_PROCESS.equals(Application.getProcessName())) return result;
                if (context instanceof Context) {
                    Context applicationContext = ((Context) context).getApplicationContext();
                    aicrContext = applicationContext != null ? applicationContext : (Context) context;
                }
                installAicrHooks();
                return result;
            });
            aicrAttachHookInstalled = true;
            logDebug("AICR Application.attach hook installed");
        } catch (Throwable throwable) {
            logError("Failed to hook AICR Application.attach", throwable);
        }
    }

    private void installAicrHooks() {
        if (aicrHooksInstalled || aicrContext == null) return;
        try {
            Method getPrimaryClip = ClipboardManager.class.getDeclaredMethod("getPrimaryClip");
            getPrimaryClip.setAccessible(true);
            hook(getPrimaryClip).setId("hypercopy_aicr_get_primary_clip").intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof ClipData) || !shouldUseCustomJumpMode()) return result;
                String clipboardText = firstClipText((ClipData) result);
                if (!clipboardText.isEmpty()) {
                    lastAicrClipboardText = clipboardText;
                    confirmPendingAicrClick();
                }
                boolean handled = handleClipboardThroughApp((ClipData) result);
                if (handled && shouldUseSystemCopyMode()) prepareAicrTarget(clipboardText);
                if (handled && shouldUseMiuiIslandMode()) {
                    logDebug("blocked matched clipboard from AICR in super-island mode");
                    return ClipData.newPlainText("", "");
                }
                return result;
            });

            Class<?> bubbleManagerClass = Class.forName(AICR_BUBBLE_MANAGER, false, aicrContext.getClassLoader());
            installAicrBubbleManagerHook(bubbleManagerClass);
            ensureAicrBubbleManager(bubbleManagerClass);
            int bubbleHookCount = 0;
            for (Method method : bubbleManagerClass.getDeclaredMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if ("g".equals(method.getName()) && parameterTypes.length == 2
                    && List.class.isAssignableFrom(parameterTypes[0])) {
                    method.setAccessible(true);
                    hook(method).setId("hypercopy_aicr_bubble_show").intercept(chain -> {
                        replaceAicrCopyCue(chain.getArg(0));
                        return chain.proceed();
                    });
                    bubbleHookCount++;
                } else if ("b".equals(method.getName()) && parameterTypes.length == 1
                    && parameterTypes[0] == String.class) {
                    method.setAccessible(true);
                    hook(method).setId("hypercopy_aicr_bubble_click").intercept(chain -> {
                        Object cueId = chain.getArg(0);
                        if (cueId instanceof String && launchAicrTarget((String) cueId)) return null;
                        return chain.proceed();
                    });
                    bubbleHookCount++;
                }
            }
            int positionHookCount = installAicrPositionHooks(aicrContext.getClassLoader());
            aicrHooksInstalled = bubbleHookCount == 2;
            logDebug("AICR clipboard and bubble hooks installed: " + bubbleHookCount
                + ", position hooks: " + positionHookCount);
        } catch (Throwable throwable) {
            logError("Failed to hook AICR clipboard actions", throwable);
        }
    }

    private void installAicrBubbleManagerHook(Class<?> bubbleManagerClass) throws Exception {
        Constructor<?> constructor = bubbleManagerClass.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        hook(constructor).setId("hypercopy_aicr_bubble_manager").intercept(chain -> {
            Object result = chain.proceed();
            Object manager = chain.getThisObject();
            if (manager != null) {
                aicrBubbleManager = manager;
                PendingAicrTarget target = pendingAicrTarget;
                if (target != null) {
                    new Handler(Looper.getMainLooper()).post(() -> injectAicrCue(target));
                }
            }
            return result;
        });
    }

    private void ensureAicrBubbleManager(Class<?> bubbleManagerClass) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (aicrBubbleManager != null) return;
            try {
                Constructor<?> constructor = bubbleManagerClass.getDeclaredConstructor(Context.class);
                constructor.setAccessible(true);
                Object manager = constructor.newInstance(aicrContext);
                if (aicrBubbleManager == null) aicrBubbleManager = manager;
                logDebug("AICR bubble manager initialized");
            } catch (Throwable throwable) {
                logWarn("initialize AICR bubble manager failed", throwable);
            }
        });
    }

    private int installAicrPositionHooks(ClassLoader classLoader) throws Exception {
        int hookCount = 0;
        Class<?> clickRecorderClass = Class.forName(AICR_CLICK_RECORDER, false, classLoader);
        for (Method method : clickRecorderClass.getDeclaredMethods()) {
            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!"J".equals(method.getName()) || parameterTypes.length != 2
                || parameterTypes[0] != String.class
                || !MotionEvent.class.isAssignableFrom(parameterTypes[1])) continue;
            method.setAccessible(true);
            hook(method).setId("hypercopy_aicr_click_position").intercept(chain -> {
                Object motion = chain.getArg(0);
                Object event = chain.getArg(1);
                if ("click".equals(motion) && event instanceof MotionEvent) {
                    pendingAicrClickY = ((MotionEvent) event).getY();
                    pendingAicrClickAt = System.currentTimeMillis();
                }
                return chain.proceed();
            });
            hookCount++;
        }

        Class<?> containerClass = Class.forName(AICR_BUBBLE_CONTAINER, false, classLoader);
        Method onLayout = containerClass.getDeclaredMethod(
            "onLayout",
            boolean.class,
            int.class,
            int.class,
            int.class,
            int.class
        );
        onLayout.setAccessible(true);
        hook(onLayout).setId("hypercopy_aicr_bubble_position").intercept(chain -> {
            positionAicrBubble(chain.getThisObject());
            return chain.proceed();
        });
        return hookCount + 1;
    }

    private boolean handleClipboardThroughApp(ClipData clipData) {
        Context context = aicrContext;
        if (context == null) return false;
        String value = firstClipText(clipData);
        if (value.isEmpty() || value.length() > Config.CLIPBOARD_TEXT_MAX_LENGTH) return false;
        try {
            Bundle result = context.getContentResolver().call(
                Uri.parse("content://" + Config.CLIPBOARD_MATCH_PROVIDER_AUTHORITY),
                Config.CLIPBOARD_MATCH_PROVIDER_METHOD,
                value,
                null
            );
            return result != null && result.getBoolean(Config.CLIPBOARD_MATCH_PROVIDER_RESULT, false);
        } catch (Throwable throwable) {
            logWarn("handle clipboard through app failed", throwable);
            return false;
        }
    }

    private void replaceAicrCopyCue(Object value) {
        if (!(value instanceof List)) return;
        try {
            for (Object cue : (List<?>) value) {
                if (cue == null || !"copy_jump".equals(invokeString(cue, "getCategory"))) continue;
                String cueId = invokeString(cue, "getCueId");
                if (cueId.isEmpty()) continue;
                aicrCopyCueIds.add(cueId);
                float clickY = recentAicrClickY();
                if (clickY >= 0f) aicrCuePositions.put(cueId, clickY);
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> {
                        aicrCopyCueIds.remove(cueId);
                        aicrCuePositions.remove(cueId);
                    },
                    AICR_TARGET_WINDOW_MILLIS
                );
                if (aicrCueTargets.containsKey(cueId)) return;
                PendingAicrTarget pendingTarget = takePendingAicrTarget(lastAicrClipboardText);
                final PendingAicrTarget target = pendingTarget != null ? pendingTarget : takeAicrTarget();
                if (target == null) return;

                Object display = cue.getClass().getMethod("getDisplay").invoke(cue);
                if (display == null) continue;
                setPublicField(display, "targetPackage", target.targetPackage);
                Object briefCard = getPublicField(display, "briefCard");
                Object title = briefCard == null ? null : getPublicField(briefCard, "title");
                if (title != null) {
                    CharSequence appName = aicrContext.getPackageManager().getApplicationLabel(
                        aicrContext.getPackageManager().getApplicationInfo(target.targetPackage, 0)
                    );
                    setPublicField(title, "text", "打开" + appName);
                }
                Object description = briefCard == null ? null : getPublicField(briefCard, "description");
                if (description != null) setPublicField(description, "text", "");
                Object startIcon = briefCard == null ? null : getPublicField(briefCard, "startIcon");
                if (startIcon != null) {
                    setPublicField(startIcon, "type", "application");
                    setPublicField(startIcon, "value", target.targetPackage);
                }

                aicrCueTargets.put(cueId, target);
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> aicrCueTargets.remove(cueId, target),
                    AICR_TARGET_WINDOW_MILLIS
                );
                logDebug("replaced AICR copy-direct cue: " + cueId + " -> " + target.targetPackage);
                return;
            }
        } catch (Throwable throwable) {
            logWarn("replace AICR copy-direct cue failed", throwable);
        }
    }

    private PendingAicrTarget takeAicrTarget() {
        if (aicrContext == null || lastAicrClipboardText.isEmpty()) return null;
        try {
            Bundle result = aicrContext.getContentResolver().call(
                Uri.parse("content://" + Config.CLIPBOARD_MATCH_PROVIDER_AUTHORITY),
                Config.CLIPBOARD_MATCH_PROVIDER_TARGET_METHOD,
                lastAicrClipboardText,
                null
            );
            if (result == null) return null;
            String targetText = result.getString(Config.EXTRA_AICR_TARGET_TEXT, "").trim();
            String targetPackage = result.getString(Config.EXTRA_AICR_TARGET_PACKAGE, "").trim();
            if (targetText.isEmpty() || targetPackage.isEmpty()) return null;
            return new PendingAicrTarget(
                lastAicrClipboardText,
                targetText,
                targetPackage,
                System.currentTimeMillis() + AICR_TARGET_WINDOW_MILLIS
            );
        } catch (Throwable throwable) {
            logWarn("take AICR copy-direct target failed", throwable);
            return null;
        }
    }

    private void prepareAicrTarget(String clipboardText) {
        if (clipboardText.isEmpty()) return;
        PendingAicrTarget target = takeAicrTarget();
        if (target == null) return;
        PendingAicrTarget current = pendingAicrTarget;
        if (current != null && !current.isExpired() && current.sameTarget(target)) return;
        pendingAicrTarget = target;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> injectAicrCue(target),
            AICR_INJECT_DELAY_MILLIS
        );
    }

    private synchronized PendingAicrTarget takePendingAicrTarget(String clipboardText) {
        PendingAicrTarget target = pendingAicrTarget;
        if (target == null || target.isExpired() || !target.sourceText.equals(clipboardText)) return null;
        pendingAicrTarget = null;
        return target;
    }

    private synchronized PendingAicrTarget claimPendingAicrTarget(PendingAicrTarget expected) {
        if (pendingAicrTarget != expected || expected.isExpired()) return null;
        pendingAicrTarget = null;
        return expected;
    }

    private void injectAicrCue(PendingAicrTarget expected) {
        Object manager = aicrBubbleManager;
        if (manager == null) return;
        PendingAicrTarget target = claimPendingAicrTarget(expected);
        if (target == null) return;
        try {
            String cueId = AICR_CUE_ID_PREFIX + System.currentTimeMillis();
            Object cue = createAicrCue(cueId, target);
            aicrCueTargets.put(cueId, target);
            aicrCopyCueIds.add(cueId);
            float clickY = recentAicrClickY();
            if (clickY >= 0f) aicrCuePositions.put(cueId, clickY);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                aicrCueTargets.remove(cueId, target);
                aicrCopyCueIds.remove(cueId);
                aicrCuePositions.remove(cueId);
            }, AICR_TARGET_WINDOW_MILLIS);
            Method addCues = manager.getClass().getDeclaredMethod("l", List.class);
            addCues.setAccessible(true);
            addCues.invoke(manager, Collections.singletonList(cue));
            logDebug("injected AICR copy-direct cue: " + cueId + " -> " + target.targetPackage);
        } catch (Throwable throwable) {
            logWarn("inject AICR copy-direct cue failed", throwable);
        }
    }

    private Object createAicrCue(String cueId, PendingAicrTarget target) throws Exception {
        CharSequence appName = aicrContext.getPackageManager().getApplicationLabel(
            aicrContext.getPackageManager().getApplicationInfo(target.targetPackage, 0)
        );
        JSONObject startIcon = new JSONObject()
            .put("type", "application")
            .put("value", target.targetPackage);
        JSONObject briefCard = new JSONObject()
            .put("startIcon", startIcon)
            .put("title", new JSONObject().put("text", "打开" + appName).put("highlights", new JSONArray()))
            .put("description", new JSONObject().put("text", "").put("highlights", new JSONArray()));
        JSONObject display = new JSONObject()
            .put("targetPackage", target.targetPackage)
            .put("position", new JSONObject().put("x", 12).put("y", 70).put("category", "global"))
            .put("briefCard", briefCard)
            .put("expandable", false);
        JSONObject cueJson = new JSONObject()
            .put("cueId", cueId)
            .put("eventId", cueId)
            .put("aliveTime", 5)
            .put("category", "copy_jump")
            .put("type", "action")
            .put("supportJumpService", true)
            .put("support_aggregate", false)
            .put("display", display)
            .put("actions", new JSONArray());
        Class<?> cueDataClass = Class.forName(AICR_CUE_DATA, false, aicrContext.getClassLoader());
        return cueDataClass.getMethod("fromJson", String.class).invoke(null, cueJson.toString());
    }

    private boolean launchAicrTarget(String cueId) {
        PendingAicrTarget target = aicrCueTargets.remove(cueId);
        if (target == null || target.isExpired() || aicrContext == null) return false;
        long identity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target.targetText))
                .setPackage(target.targetPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            aicrContext.startActivity(intent);
            logDebug("opened AICR copy-direct target: " + target.targetPackage);
            return true;
        } catch (Throwable throwable) {
            logWarn("open AICR copy-direct target failed", throwable);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private float recentAicrClickY() {
        long age = System.currentTimeMillis() - lastAicrClickAt;
        return age >= 0L && age <= AICR_CLICK_WINDOW_MILLIS ? lastAicrClickY : -1f;
    }

    private void confirmPendingAicrClick() {
        long clickAt = pendingAicrClickAt;
        float clickY = pendingAicrClickY;
        pendingAicrClickAt = 0L;
        pendingAicrClickY = -1f;
        long age = System.currentTimeMillis() - clickAt;
        if (age < 0L || age > AICR_CLICK_WINDOW_MILLIS) return;
        lastAicrClickY = clickY;
        lastAicrClickAt = clickAt;
        logDebug("confirmed AICR clipboard click y=" + clickY);
    }

    private void positionAicrBubble(Object value) {
        if (!(value instanceof ViewGroup)) return;
        ViewGroup container = (ViewGroup) value;
        for (String cueId : aicrCopyCueIds) {
            try {
                Object holder = container.getClass().getMethod("b", String.class)
                    .invoke(container, cueId);
                Object bubble = holder == null ? null : getPublicField(holder, "a");
                if (!(bubble instanceof View)) continue;
                View bubbleView = (View) bubble;
                applyAicrSystemTypeface(bubbleView);

                Float clickY = aicrCuePositions.get(cueId);
                if (clickY == null) continue;
                int bubbleHeight = bubbleView.getMeasuredHeight();
                int containerHeight = container.getMeasuredHeight();
                if (bubbleHeight <= 0 || containerHeight <= 0) continue;

                int topInset = 0;
                int bottomInset = 0;
                WindowInsets windowInsets = container.getRootWindowInsets();
                if (windowInsets != null) {
                    Insets insets = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    );
                    topInset = insets.top;
                    bottomInset = insets.bottom;
                }
                int bottomMargin = Math.round(
                    containerHeight - bottomInset - clickY - bubbleHeight / 2f
                );
                int maxBottomMargin = Math.max(
                    0,
                    containerHeight - topInset - bottomInset - bubbleHeight
                );
                bottomMargin = Math.max(0, Math.min(bottomMargin, maxBottomMargin));
                Object currentMargin = getPublicField(container, "c");
                if (!(currentMargin instanceof Integer) || (Integer) currentMargin != bottomMargin) {
                    setPublicField(container, "c", bottomMargin);
                    logDebug("positioned AICR copy-direct cue at y=" + clickY
                        + ", bottomMargin=" + bottomMargin);
                }
            } catch (Throwable throwable) {
                logWarn("update AICR copy-direct cue failed", throwable);
            }
        }
    }

    private void applyAicrSystemTypeface(View bubble) {
        try {
            Object title = getPublicField(bubble, "c");
            if (title instanceof TextView) {
                if (systemThemeTypeface == null) {
                    try {
                        systemThemeTypeface = Typeface.createFromFile(SYSTEM_THEME_FONT_PATH);
                    } catch (Throwable ignored) {
                        systemThemeTypeface = Typeface.DEFAULT;
                    }
                }
                ((TextView) title).setTypeface(systemThemeTypeface);
            }
        } catch (Throwable throwable) {
            logWarn("apply system typeface failed", throwable);
        }
    }

    private static String invokeString(Object target, String methodName) throws Exception {
        Object value = target.getClass().getMethod(methodName).invoke(target);
        return value == null ? "" : value.toString();
    }

    private static Object getPublicField(Object target, String fieldName) throws Exception {
        return target.getClass().getField(fieldName).get(target);
    }

    private static void setPublicField(Object target, String fieldName, Object value) throws Exception {
        target.getClass().getField(fieldName).set(target, value);
    }

    private static String getDeclaredString(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String firstClipText(ClipData clipData) {
        if (clipData == null || clipData.getItemCount() == 0) return "";
        CharSequence text = clipData.getItemAt(0).getText();
        return text == null ? "" : text.toString().trim();
    }

    private boolean shouldUseSystemCopyMode() {
        return Config.JUMP_NOTIFICATION_MODE_SYSTEM_COPY.equals(readJumpNotificationMode());
    }

    private boolean shouldUseMiuiIslandMode() {
        return Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND.equals(readJumpNotificationMode());
    }

    private boolean shouldUseCustomJumpMode() {
        String mode = readJumpNotificationMode();
        return Config.JUMP_NOTIFICATION_MODE_MIUI_ISLAND.equals(mode)
            || Config.JUMP_NOTIFICATION_MODE_SYSTEM_COPY.equals(mode);
    }

    private String readJumpNotificationMode() {
        try {
            android.content.SharedPreferences preferences = getRemotePreferences(Config.PREFS_NAME);
            return preferences.getString(
                Config.KEY_LSPOSED_JUMP_NOTIFICATION_MODE,
                preferences.getString(Config.KEY_JUMP_NOTIFICATION_MODE, Config.DEFAULT_JUMP_NOTIFICATION_MODE)
            );
        } catch (Throwable ignored) {
            return Config.DEFAULT_JUMP_NOTIFICATION_MODE;
        }
    }

    private static final class PendingAicrTarget {
        private final String sourceText;
        private final String targetText;
        private final String targetPackage;
        private final long expiresAt;

        private PendingAicrTarget(String sourceText, String targetText, String targetPackage, long expiresAt) {
            this.sourceText = sourceText;
            this.targetText = targetText;
            this.targetPackage = targetPackage;
            this.expiresAt = expiresAt;
        }

        private boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }

        private boolean sameTarget(PendingAicrTarget other) {
            return sourceText.equals(other.sourceText)
                && targetText.equals(other.targetText)
                && targetPackage.equals(other.targetPackage);
        }
    }

    private void installClipboardHooksWithRetry(ClassLoader classLoader, String source, int attempt) {
        if (hooksInstalled) return;
        if (installClipboardHooks(classLoader, source + ", attempt=" + attempt)) return;
        if (attempt >= INSTALL_RETRY_LIMIT) return;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> installClipboardHooksWithRetry(classLoader, source, attempt + 1),
            INSTALL_RETRY_DELAY_MILLIS
        );
    }

    private boolean installClipboardHooks(ClassLoader classLoader, String source) {
        if (hooksInstalled) {
            logDebug("ClipboardService hooks already installed, source=" + source);
            return true;
        }
        logDebug("installing ClipboardService hooks, source=" + source);
        try {
            Class<?> clipboardServiceClass = Class.forName(CLIPBOARD_SERVICE_CLASS, false, classLoader);
            Set<Method> hookedMethods = new HashSet<>();
            int hookedCount = hookClipboardMethods(clipboardServiceClass, hookedMethods);
            for (Class<?> declaredClass : clipboardServiceClass.getDeclaredClasses()) {
                hookedCount += hookClipboardMethods(declaredClass, hookedMethods);
            }
            hooksInstalled = hookedCount > 0;
            if (hooksInstalled) {
                registerClearReceiverWithRetry(0);
            }
            logDebug("ClipboardService hooks installed: " + hookedCount);
            return hooksInstalled;
        } catch (ClassNotFoundException throwable) {
            logDebug("ClipboardService not ready, source=" + source + ", classLoader=" + classLoader);
            return false;
        } catch (Throwable throwable) {
            logError("Failed to hook ClipboardService", throwable);
            return false;
        }
    }

    private int hookClipboardMethods(Class<?> targetClass, Set<Method> hookedMethods) {
        int hookedCount = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            if (!isSetPrimaryClipMethod(method) || !hookedMethods.add(method)) continue;
            method.setAccessible(true);
            logDebug("hook ClipboardService method: " + method.toGenericString());
            hook(method).setId("hypercopy_clipboard_" + method.toGenericString()).intercept(chain -> {
                int callingUid = Binder.getCallingUid();
                Object[] args = chain.getArgs().toArray();
                ClipData originalClipData = findClipData(args);
                boolean systemCopyMode = shouldUseSystemCopyMode();
                Context context = findContext(chain.getThisObject());
                if (context == null) context = findSystemContext();
                boolean handledByHyperCopy = systemCopyMode
                    && sendTextIfNeeded(context, originalClipData, args, callingUid);
                Object[] callArgs = handledByHyperCopy ? suppressNativeBar(args) : args;
                if (handledByHyperCopy && originalClipData != null) {
                    logDebug("suppressed native clipboard action, label="
                        + String.valueOf(originalClipData.getDescription().getLabel()));
                }
                Object result = chain.proceed(callArgs);
                try {
                    if (!systemCopyMode) {
                        sendTextIfNeeded(context, findClipData(callArgs), callArgs, callingUid);
                    }
                } catch (Throwable throwable) {
                    logWarn("clipboard hook callback failed", throwable);
                }
                return result;
            });
            hookedCount++;
        }
        return hookedCount;
    }

    private static boolean isSetPrimaryClipMethod(Method method) {
        if (!method.getName().startsWith("setPrimaryClip")) return false;
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (ClipData.class.isAssignableFrom(parameterType)) return true;
        }
        return false;
    }

    private static ClipData findClipData(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ClipData) return (ClipData) arg;
        }
        return null;
    }

    private static Object[] suppressNativeBar(Object[] args) {
        for (int index = 0; index < args.length; index++) {
            if (!(args[index] instanceof ClipData)) continue;
            ClipData original = (ClipData) args[index];
            if (original.getItemCount() == 0) continue;
            ClipDescription description = original.getDescription();
            String[] mimeTypes = new String[description.getMimeTypeCount()];
            for (int mimeIndex = 0; mimeIndex < mimeTypes.length; mimeIndex++) {
                mimeTypes[mimeIndex] = description.getMimeType(mimeIndex);
            }
            ClipData replacement = new ClipData(
                "universalClipData",
                mimeTypes,
                original.getItemAt(0)
            );
            for (int itemIndex = 1; itemIndex < original.getItemCount(); itemIndex++) {
                replacement.addItem(original.getItemAt(itemIndex));
            }
            args[index] = replacement;
            break;
        }
        return args;
    }

    private static Context findContext(Object service) {
        return findContext(service, new HashSet<>(), 0);
    }

    private static Context findContext(Object service, Set<Object> visited, int depth) {
        if (service == null || depth > 2 || !visited.add(service)) return null;
        Class<?> current = service.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(service);
                    if (value instanceof Context) return (Context) value;
                    if (field.isSynthetic() || field.getName().startsWith("this$")) {
                        Context context = findContext(value, visited, depth + 1);
                        if (context != null) return context;
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Context findSystemContext() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThreadClass.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);
            Object activityThread = currentActivityThread.invoke(null);
            if (activityThread == null) return null;
            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Object context = getSystemContext.invoke(activityThread);
            if (context instanceof Context) return (Context) context;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void registerClearReceiverWithRetry(int attempt) {
        if (clearReceiverRegistered) return;
        if (registerClearReceiver(findSystemContext(), attempt)) return;
        if (attempt >= CLEAR_RECEIVER_RETRY_LIMIT) return;
        new Handler(Looper.getMainLooper()).postDelayed(
            () -> registerClearReceiverWithRetry(attempt + 1),
            CLEAR_RECEIVER_RETRY_DELAY_MILLIS
        );
    }

    private boolean registerClearReceiver(Context context, int attempt) {
        if (clearReceiverRegistered) return true;
        if (context == null) {
            logDebug("clipboard clear receiver context not ready, attempt=" + attempt);
            return false;
        }
        try {
            IntentFilter filter = new IntentFilter(Config.ACTION_CLEAR_CLIPBOARD);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (!Config.ACTION_CLEAR_CLIPBOARD.equals(intent.getAction())) return;
                    try {
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            clipboard.clearPrimaryClip();
                        } else {
                            clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                        }
                        setResultCode(Activity.RESULT_OK);
                        logDebug("clipboard cleared in system_server by LSPosed");
                    } catch (Throwable throwable) {
                        setResultCode(Activity.RESULT_CANCELED);
                        logWarn("clear clipboard in system_server failed", throwable);
                    }
                }
            }, filter, Config.PERMISSION_CLEAR_CLIPBOARD, null, Context.RECEIVER_EXPORTED);
            clearReceiverRegistered = true;
            logDebug("clipboard clear receiver registered");
            return true;
        } catch (Throwable throwable) {
            logWarn("register clipboard clear receiver failed, attempt=" + attempt, throwable);
            return false;
        }
    }

    private synchronized boolean sendTextIfNeeded(Context context, ClipData clipData, Object[] args, int callingUid) {
        if (context == null || clipData == null || clipData.getItemCount() == 0) return false;
        CharSequence text = extractPlainText(context, clipData);
        if (text == null) return false;

        String value = text.toString().trim();
        if (value.isEmpty() || value.length() > Config.CLIPBOARD_TEXT_MAX_LENGTH) return false;

        long now = System.currentTimeMillis();
        if (value.equals(lastText) && now - lastSentAt < DUPLICATE_WINDOW_MILLIS) return lastSentHandled;
        lastText = value;
        lastSentAt = now;
        lastSentHandled = false;
        String sourcePackage = findSourcePackage(context, args, callingUid);
        int sourceUserId = callingUid / 100_000;

        logDebug("handle clipboard text, length=" + value.length() + ", source=" + sourcePackage
            + ", uid=" + callingUid + ", user=" + sourceUserId);
        long identity = Binder.clearCallingIdentity();
        try {
            Bundle extras = new Bundle();
            extras.putString(Config.EXTRA_CLIPBOARD_SOURCE, sourcePackage);
            Bundle result = context.getContentResolver().call(
                Uri.parse("content://" + Config.CLIPBOARD_MATCH_PROVIDER_AUTHORITY),
                Config.CLIPBOARD_MATCH_PROVIDER_METHOD,
                value,
                extras
            );
            lastSentHandled = result != null && result.getBoolean(Config.CLIPBOARD_MATCH_PROVIDER_RESULT, false);
            return lastSentHandled;
        } catch (Throwable throwable) {
            logWarn("clipboard provider call failed", throwable);
            return false;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static String findSourcePackage(Context context, Object[] args, int callingUid) {
        String[] callingPackages = context.getPackageManager().getPackagesForUid(callingUid);
        if (callingPackages != null && callingPackages.length > 0) return callingPackages[0];
        if (args == null) return "";
        for (Object arg : args) {
            if (!(arg instanceof String)) continue;
            String value = ((String) arg).trim();
            if (looksLikePackageName(value)) return value;
        }
        for (Object arg : args) {
            if (!(arg instanceof Integer)) continue;
            int uid = (Integer) arg;
            if (uid < 10_000) continue;
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null && packages.length > 0) return packages[0];
        }
        return "";
    }

    private static boolean looksLikePackageName(String value) {
        return !value.isEmpty() && value.contains(".") && !value.contains(" ");
    }

    private static CharSequence extractPlainText(Context context, ClipData clipData) {
        ClipDescription description = clipData.getDescription();
        if (description == null) return null;
        boolean textMime = description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
            || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML);
        if (!textMime) return null;

        ClipData.Item item = clipData.getItemAt(0);
        if (item == null || item.getUri() != null || item.getIntent() != null) return null;
        if (item.getText() != null) return item.getText();
        if (item.getHtmlText() != null) return item.getHtmlText();
        return item.coerceToText(context);
    }

    private void logDebug(String message) {
        HookLog.d(this, TAG, message);
    }

    private void logWarn(String message, Throwable throwable) {
        HookLog.w(this, TAG, message, throwable);
    }

    private void logError(String message, Throwable throwable) {
        HookLog.e(this, TAG, message, throwable);
    }
}
