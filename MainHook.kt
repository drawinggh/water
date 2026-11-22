package com.waterdrop.module

import android.content.Context
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * MainHook.kt
 * 
 * ماژول LSPosed برای نمایش افکت قطرات آب روی لاک اسکرین.
 * این فایل روی SystemUI هوک می‌زند و یک Overlay شفاف
 * با انیمیشن قطرات آب اضافه می‌کند.
 *
 * نصب:
 *   1. ماژول را در LSPosed فعال کن
 *   2. Scope را فقط روی com.android.systemui بذار
 *   3. گوشی را ریستارت کن
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "WaterDropHook"
        private const val TARGET_PACKAGE = "com.android.systemui"
        @Volatile
        private var overlayAdded = false
        @Volatile
        private var overlayView: WaterDropOverlay? = null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // فقط روی SystemUI اجرا شود
        if (lpparam.packageName != TARGET_PACKAGE) return

        XposedBridge.log("$TAG: SystemUI detected, starting hooks...")

        // روش ۱: هوک روی lifecycle SystemUI (قابل اعتمادترین روش)
        try {
            hookSystemUIApplication(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: SystemUIApplication hook failed: ${e.message}")
        }

        // روش ۲: هوک روی KeyguardViewMediator (برای لاک اسکرین)
        try {
            hookKeyguardMediator(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: KeyguardMediator hook failed: ${e.message}")
        }

        // روش ۳: هوک روی StatusBar (fallback)
        try {
            hookStatusBar(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: StatusBar hook failed: ${e.message}")
        }
    }

    /**
     * هوک روی SystemUIApplication.onCreate
     * این نقطه مطمئن‌ترین جایی است که Context کامل SystemUI در دسترس است.
     */
    private fun hookSystemUIApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        val className = "com.android.systemui.SystemUIApplication"

        XposedHelpers.findAndHookMethod(
            className,
            lpparam.classLoader,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Context ?: return
                    // کمی تأخیر تا UI کاملاً آماده شود
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        addWaterOverlay(app)
                    }, 2000)
                }
            }
        )
        XposedBridge.log("$TAG: Hooked $className.onCreate")
    }

    /**
     * هوک روی KeyguardViewMediator.showLocked
     * وقتی لاک اسکرین نمایش داده می‌شود، قطرات فعال می‌شوند.
     */
    private fun hookKeyguardMediator(lpparam: XC_LoadPackage.LoadPackageParam) {
        val className = "com.android.systemui.keyguard.KeyguardViewMediator"

        XposedHelpers.findAndHookMethod(
            className,
            lpparam.classLoader,
            "showLocked",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val mediator = param.thisObject
                        val context = XposedHelpers.callMethod(mediator, "getContext") as? Context
                            ?: XposedHelpers.getObjectField(mediator, "mContext") as? Context
                            ?: return

                        if (overlayView != null) {
                            // فقط visibility را تغییر بده، دوباره add نکن
                            overlayView?.visibility = android.view.View.VISIBLE
                        } else {
                            addWaterOverlay(context)
                        }
                    } catch (e: Throwable) {
                        XposedBridge.log("$TAG: showLocked error: ${e.message}")
                    }
                }
            }
        )

        // وقتی لاک اسکرین بسته می‌شود، قطرات را مخفی کن
        XposedHelpers.findAndHookMethod(
            className,
            lpparam.classLoader,
            "hideLocked",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    overlayView?.visibility = android.view.View.GONE
                }
            }
        )

        XposedBridge.log("$TAG: Hooked $className.showLocked/hideLocked")
    }

    /**
     * هوک روی StatusBar.makeStatusBarWindow
     * برای ROMهایی که KeyguardViewMediator متفاوت دارند.
     */
    private fun hookStatusBar(lpparam: XC_LoadPackage.LoadPackageParam) {
        val candidates = listOf(
            "com.android.systemui.statusbar.phone.StatusBar",
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl"
        )

        for (className in candidates) {
            try {
                XposedHelpers.findAndHookMethod(
                    className,
                    lpparam.classLoader,
                    "onStart",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val context = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                                ?: return
                            if (!overlayAdded) addWaterOverlay(context)
                        }
                    }
                )
                XposedBridge.log("$TAG: Hooked $className.onStart")
                break
            } catch (e: Throwable) {
                // این کلاس در این ROM وجود ندارد، بعدی را امتحان کن
            }
        }
    }

    /**
     * اضافه کردن ویوی قطرات آب به WindowManager.
     * از TYPE_KEYGUARD_DIALOG استفاده می‌شود تا روی لاک اسکرین نمایش داده شود.
     */
    private fun addWaterOverlay(context: Context) {
        if (overlayAdded) return

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return

            val overlay = WaterDropOverlay(context)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                // این type اجازه می‌دهد روی لاک اسکرین نمایش داده شود
                WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG,
                // FLAG_NOT_FOCUSABLE: فوکوس را نمی‌گیرد
                // FLAG_NOT_TOUCHABLE: تاچ را مسدود نمی‌کند
                // FLAG_LAYOUT_IN_SCREEN: کل صفحه را پوشش می‌دهد
                // FLAG_SHOW_WHEN_LOCKED: روی لاک اسکرین هم نمایش داده می‌شود
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                android.graphics.PixelFormat.TRANSLUCENT
            )

            // برای Android 12+ نیاز به format و alpha
            params.alpha = 0.95f
            params.format = android.graphics.PixelFormat.TRANSLUCENT

            wm.addView(overlay, params)
            overlay.startAnimation()

            overlayView = overlay
            overlayAdded = true

            XposedBridge.log("$TAG: ✅ Water overlay added successfully")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: ❌ Failed to add overlay: ${e.message}")
            // fallback با type ساده‌تر
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
                val overlay = WaterDropOverlay(context)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT
                )
                wm.addView(overlay, params)
                overlay.startAnimation()
                overlayView = overlay
                overlayAdded = true
                XposedBridge.log("$TAG: ⚠️ Fallback overlay added (may not show on lock screen)")
            } catch (e2: Throwable) {
                XposedBridge.log("$TAG: ❌ Fallback also failed: ${e2.message}")
            }
        }
    }
}
