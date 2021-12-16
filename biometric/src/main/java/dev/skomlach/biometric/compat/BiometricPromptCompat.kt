/*
 *  Copyright (c) 2021 Sergey Komlach aka Salat-Cx65; Original project: https://github.com/Salat-Cx65/AdvancedBiometricPromptCompat
 *  All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package dev.skomlach.biometric.compat

import android.content.DialogInterface
import android.graphics.Color
import android.os.Build
import android.os.Looper
import androidx.annotation.ColorInt
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.skomlach.biometric.compat.BiometricManagerCompat.hasEnrolled
import dev.skomlach.biometric.compat.BiometricManagerCompat.isBiometricEnrollChanged
import dev.skomlach.biometric.compat.BiometricManagerCompat.isBiometricSensorPermanentlyLocked
import dev.skomlach.biometric.compat.BiometricManagerCompat.isHardwareDetected
import dev.skomlach.biometric.compat.BiometricManagerCompat.isLockOut
import dev.skomlach.biometric.compat.engine.BiometricAuthentication
import dev.skomlach.biometric.compat.engine.BiometricInitListener
import dev.skomlach.biometric.compat.engine.BiometricMethod
import dev.skomlach.biometric.compat.engine.core.interfaces.BiometricModule
import dev.skomlach.biometric.compat.engine.internal.AbstractBiometricModule
import dev.skomlach.biometric.compat.impl.BiometricPromptApi28Impl
import dev.skomlach.biometric.compat.impl.BiometricPromptGenericImpl
import dev.skomlach.biometric.compat.impl.IBiometricPromptImpl
import dev.skomlach.biometric.compat.impl.PermissionsFragment
import dev.skomlach.biometric.compat.utils.*
import dev.skomlach.biometric.compat.utils.activityView.ActivityViewWatcher
import dev.skomlach.biometric.compat.utils.device.DeviceInfo
import dev.skomlach.biometric.compat.utils.device.DeviceInfoManager
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl
import dev.skomlach.biometric.compat.utils.notification.BiometricNotificationManager
import dev.skomlach.biometric.compat.utils.statusbar.StatusBarTools
import dev.skomlach.biometric.compat.utils.themes.DarkLightThemes
import dev.skomlach.common.contextprovider.AndroidContext
import dev.skomlach.common.logging.LogCat
import dev.skomlach.common.misc.ExecutorHelper
import dev.skomlach.common.misc.isActivityFinished
import dev.skomlach.common.misc.multiwindow.MultiWindowSupport
import dev.skomlach.common.permissions.PermissionUtils
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BiometricPromptCompat private constructor(private val builder: Builder) {
    companion object {
        var API_ENABLED = true
            private set

        init {
            if (API_ENABLED) {
                AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        HiddenApiBypass.setHiddenApiExemptions("L");
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            }
        }

        @JvmStatic
        fun apiEnabled(enabled: Boolean) {
            API_ENABLED = enabled
        }

        private val availableAuthRequests = ArrayList<BiometricAuthRequest>()

        @JvmStatic
        fun getAvailableAuthRequests(): List<BiometricAuthRequest> {
            return availableAuthRequests
        }

        @JvmStatic
        fun logging(enabled: Boolean) {
            if (!API_ENABLED)
                return
//            AbstractBiometricModule.DEBUG_MANAGERS = enabled
            LogCat.DEBUG = enabled
            BiometricLoggerImpl.DEBUG = enabled
        }

        private val pendingTasks: MutableList<Runnable?> =
            Collections.synchronizedList(ArrayList<Runnable?>())
        private var isBiometricInit = AtomicBoolean(false)
        var isInit = false
            get() = isBiometricInit.get()
            private set
        private var initInProgress = AtomicBoolean(false)
        var deviceInfo: DeviceInfo? = null
            private set
            get() {
                if (field == null && !isDeviceInfoCheckInProgress) {
                    ExecutorHelper.startOnBackground {
                        isDeviceInfoCheckInProgress = true
                        DeviceInfoManager.getDeviceInfo(object :
                            DeviceInfoManager.OnDeviceInfoListener {
                            override fun onReady(info: DeviceInfo?) {
                                isDeviceInfoCheckInProgress = false
                                field = info
                            }
                        })
                    }
                }
                return field
            }

        @Volatile
        private var isDeviceInfoCheckInProgress = false

        @MainThread
        @JvmStatic
        fun init(execute: Runnable? = null) {
            if (!API_ENABLED)
                return
            if (Looper.getMainLooper().thread !== Thread.currentThread())
                throw IllegalThreadStateException("Main Thread required")

            if (isBiometricInit.get()) {
                BiometricLoggerImpl.d("BiometricPromptCompat.init() - ready")
                execute?.let { ExecutorHelper.post(it) }
            } else {
                if (initInProgress.get()) {
                    BiometricLoggerImpl.d("BiometricPromptCompat.init() - pending")
                    pendingTasks.add(execute)
                } else {
                    BiometricLoggerImpl.d("BiometricPromptCompat.init()")
                    isBiometricInit.set(false)
                    initInProgress.set(true)
                    pendingTasks.add(execute)
                    AndroidContext.appContext
                    startBiometricInit()
                    ExecutorHelper.startOnBackground {
                        isDeviceInfoCheckInProgress = true
                        DeviceInfoManager.getDeviceInfo(object :
                            DeviceInfoManager.OnDeviceInfoListener {
                            override fun onReady(info: DeviceInfo?) {
                                isDeviceInfoCheckInProgress = false
                                deviceInfo = info
                            }
                        })
                    }
                    DeviceUnlockedReceiver.registerDeviceUnlockListener()
                }
            }
        }

        @MainThread
        @JvmStatic
        private fun startBiometricInit() {
            BiometricAuthentication.init(object : BiometricInitListener {
                override fun initFinished(
                    method: BiometricMethod,
                    module: BiometricModule?
                ) {
                }

                override fun onBiometricReady() {
                    BiometricLoggerImpl.d("BiometricPromptCompat.init() - finished")
                    isBiometricInit.set(true)
                    initInProgress.set(false)
                    //Add default first
                    var biometricAuthRequest = BiometricAuthRequest()
                    if (isHardwareDetected(biometricAuthRequest)) {
                        availableAuthRequests.add(biometricAuthRequest)
                    }

                    for (api in BiometricApi.values()) {
                        for (type in BiometricType.values()) {
                            if (type == BiometricType.BIOMETRIC_ANY)
                                continue
                            biometricAuthRequest = BiometricAuthRequest(api, type)
                            if (isHardwareDetected(biometricAuthRequest)) {
                                availableAuthRequests.add(biometricAuthRequest)
                                //just cache value
                                hasEnrolled(biometricAuthRequest)
                                isLockOut(biometricAuthRequest)
                                isBiometricEnrollChanged(biometricAuthRequest)
                            }
                        }
                    }

                    for (task in pendingTasks) {
                        task?.let { ExecutorHelper.post(it) }
                    }
                    pendingTasks.clear()
                }
            })

        }
    }

    private val impl: IBiometricPromptImpl by lazy {
        val isBiometricPrompt =
            builder.getBiometricAuthRequest().api == BiometricApi.BIOMETRIC_API ||
                    if (builder.getBiometricAuthRequest().api == BiometricApi.AUTO && HardwareAccessImpl.getInstance(
                            builder.getBiometricAuthRequest()
                        ).isNewBiometricApi
                    ) {
                        var found = false
                        for (v in builder.getPrimaryAvailableTypes()) {
                            val request = BiometricAuthRequest(BiometricApi.BIOMETRIC_API, v)
                            if (BiometricManagerCompat.isBiometricReady(request)){
                                found = true
                                break
                            }
                        }
                        found
                    } else {
                        false
                    }
        BiometricLoggerImpl.d(
            "BiometricPromptCompat.IBiometricPromptImpl - " +
                    "$isBiometricPrompt"
        )
        val iBiometricPromptImpl = if (isBiometricPrompt) {
            BiometricPromptApi28Impl(builder)
        } else {
            BiometricPromptGenericImpl(builder)
        }
        iBiometricPromptImpl
    }

    fun authenticate(callbackOuter: AuthenticationCallback) {

        if (isActivityFinished(builder.getContext())) {
            BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause of Activity destroyed")
            return
        }
        if (!API_ENABLED) {
            callbackOuter.onFailed(AuthenticationFailureReason.NO_HARDWARE)
            return
        }
        BiometricLoggerImpl.d("BiometricPromptCompat.authenticate()")
        if(WideGamutBug.unsupportedColorMode(builder.getContext())){
            callbackOuter.onFailed(AuthenticationFailureReason.HARDWARE_UNAVAILABLE)
            return
        }
        val startTime = System.currentTimeMillis()
        var timeout = false
        ExecutorHelper.startOnBackground {
            while (!builder.isTruncateChecked() || isDeviceInfoCheckInProgress || !isInit) {
                timeout = System.currentTimeMillis() - startTime >= TimeUnit.SECONDS.toMillis(5)
                if (timeout) {
                    break
                }
                try {
                    Thread.sleep(250)
                } catch (ignore: InterruptedException) {
                }
            }
            ExecutorHelper.post {
                if (timeout) {
                    callbackOuter.onFailed(AuthenticationFailureReason.NOT_INITIALIZED_ERROR)
                } else
                    startAuth(callbackOuter)
            }
        }
    }

    private fun startAuth(callbackOuter: AuthenticationCallback) {
        if (isActivityFinished(builder.getContext())) {
            BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause of Activity destroyed")
            callbackOuter.onCanceled()
            return
        }
        //Case for Pixel 4
        val isFaceId = impl.builder.getAllAvailableTypes().contains(BiometricType.BIOMETRIC_FACE) ||
                (impl.builder.getAllAvailableTypes().size == 1 && impl.builder.getAllAvailableTypes()
                    .toList()[0] == BiometricType.BIOMETRIC_ANY
                        && DeviceInfoManager.hasFaceID(deviceInfo))
        if (isFaceId &&
            SensorPrivacyCheck.isCameraBlocked()
        ) {
            BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause camera blocked")
            callbackOuter.onCanceled()
            return
        } else if (impl.builder.getAllAvailableTypes().contains(BiometricType.BIOMETRIC_VOICE) &&
            SensorPrivacyCheck.isMicrophoneBlocked()
        ) {
            BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause mic blocked")
            callbackOuter.onCanceled()
            return
        } else if (isFaceId &&
                SensorPrivacyCheck.isCameraInUse()
            ) {
                BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause camera in use")
                callbackOuter.onFailed(AuthenticationFailureReason.LOCKED_OUT)
                return
            } else if (impl.builder.getAllAvailableTypes().contains(BiometricType.BIOMETRIC_VOICE) &&
                SensorPrivacyCheck.isMicrophoneInUse()
            ) {
                BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause mic in use")
                callbackOuter.onFailed(AuthenticationFailureReason.LOCKED_OUT)
                return
            }


        BiometricLoggerImpl.d("BiometricPromptCompat.startAuth")
        val activityViewWatcher = try {
            ActivityViewWatcher(impl.builder, object : ActivityViewWatcher.ForceToCloseCallback {
                override fun onCloseBiometric() {
                    cancelAuthentication()
                }
            })
        } catch (e: Throwable) {
            null
        }

        if (activityViewWatcher == null) {
            BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause no active windows")
            callbackOuter.onCanceled()
            return
        }

        val callback = object : AuthenticationCallback() {

            private var isOpened = AtomicBoolean(false)
            override fun onSucceeded(confirmed: Set<BiometricType>) {
                try {
                    if (builder.getBiometricAuthRequest().api != BiometricApi.AUTO) {
                        HardwareAccessImpl.getInstance(builder.getBiometricAuthRequest())
                            .updateBiometricEnrollChanged()
                    } else {
                        HardwareAccessImpl.getInstance(
                            BiometricAuthRequest(
                                BiometricApi.BIOMETRIC_API,
                                builder.getBiometricAuthRequest().type
                            )
                        )
                            .updateBiometricEnrollChanged()
                        HardwareAccessImpl.getInstance(
                            BiometricAuthRequest(
                                BiometricApi.LEGACY_API,
                                builder.getBiometricAuthRequest().type
                            )
                        )
                            .updateBiometricEnrollChanged()
                    }

                    callbackOuter.onSucceeded(confirmed)
                } finally {
                    onUIClosed()
                }
            }

            override fun onCanceled() {
                try {
                    callbackOuter.onCanceled()
                } finally {
                    onUIClosed()
                }
            }

            override fun onFailed(reason: AuthenticationFailureReason?) {
                try{
                callbackOuter.onFailed(reason)
                 } finally {
                    onUIClosed()
                }
            }

            override fun onUIOpened() {
                if (!isOpened.get()) {
                    isOpened.set(true)
                    callbackOuter.onUIOpened()
                    if (DeviceInfoManager.hasUnderDisplayFingerprint(deviceInfo) && builder.isNotificationEnabled()) {
                        BiometricNotificationManager.showNotification(builder)
                    }
                    if (impl is BiometricPromptApi28Impl) {
                        StatusBarTools.setNavBarAndStatusBarColors(
                            builder.getContext().window,
                            DialogMainColor.getColor( builder.getContext(), DarkLightThemes.isNightMode(builder.getContext())),
                            DialogMainColor.getColor( builder.getContext(), !DarkLightThemes.isNightMode(builder.getContext())),
                            builder.getStatusBarColor()
                        )
                    }
                    activityViewWatcher.setupListeners()
                }
            }

            override fun onUIClosed() {
                if (isOpened.get()) {
                    isOpened.set(false)
                    val closeAll = Runnable {
                        if (DeviceInfoManager.hasUnderDisplayFingerprint(deviceInfo) && builder.isNotificationEnabled()) {
                            BiometricNotificationManager.dismissAll()
                        }
                        activityViewWatcher.resetListeners()
                        StatusBarTools.setNavBarAndStatusBarColors(
                            builder.getContext().window,
                            builder.getNavBarColor(),
                            builder.getDividerColor(),
                            builder.getStatusBarColor()
                        )
                    }
                    ExecutorHelper.post(closeAll)
                    val delay =
                        AndroidContext.appContext.resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                    ExecutorHelper.postDelayed(closeAll, delay)
                    callbackOuter.onUIClosed()
                }
            }
        }

        if (!isHardwareDetected(impl.builder.getBiometricAuthRequest())) {
            callback.onFailed(AuthenticationFailureReason.NO_HARDWARE)
            return
        }
        if (!hasEnrolled(impl.builder.getBiometricAuthRequest())) {
            callback.onFailed(AuthenticationFailureReason.NO_BIOMETRICS_REGISTERED)
            return
        }
        if (isLockOut(impl.builder.getBiometricAuthRequest())) {
            callback.onFailed(AuthenticationFailureReason.LOCKED_OUT)
            return
        }
        if (isBiometricSensorPermanentlyLocked(impl.builder.getBiometricAuthRequest())) {
            callback.onFailed(AuthenticationFailureReason.HARDWARE_UNAVAILABLE)
            return
        }
        BiometricLoggerImpl.d("BiometricPromptCompat. start PermissionsFragment.askForPermissions")
        PermissionsFragment.askForPermissions(
            impl.builder.getContext(),
            usedPermissions
        ) {
            if (usedPermissions.isNotEmpty() && !PermissionUtils.hasSelfPermissions(
                    usedPermissions
                )
            ) {
                callback.onFailed(AuthenticationFailureReason.MISSING_PERMISSIONS_ERROR)
            } else
                authenticateInternal(callback)
        }
    }
    private val usedPermissions: List<String>
        get() {

            val permission: MutableSet<String> = HashSet()

            if (Build.VERSION.SDK_INT >= 28) {
                permission.add("android.permission.USE_BIOMETRIC")
            }

            val biometricMethodList: MutableList<BiometricMethod> = ArrayList()
            for (m in BiometricAuthentication.availableBiometricMethods) {
                if (builder.getAllAvailableTypes().contains(m.biometricType)) {
                    biometricMethodList.add(m)
                }
            }
            for (method in biometricMethodList) {
                when (method) {
                    BiometricMethod.DUMMY_BIOMETRIC -> permission.add("android.permission.CAMERA")
                    BiometricMethod.IRIS_ANDROIDAPI -> permission.add("android.permission.USE_IRIS")
                    BiometricMethod.IRIS_SAMSUNG -> permission.add("com.samsung.android.camera.iris.permission.USE_IRIS")
                    BiometricMethod.FACELOCK -> permission.add("android.permission.WAKE_LOCK")
                    BiometricMethod.FACE_HUAWEI, BiometricMethod.FACE_SOTERAPI -> permission.add("android.permission.USE_FACERECOGNITION")
                    BiometricMethod.FACE_ANDROIDAPI -> permission.add("android.permission.USE_FACE_AUTHENTICATION")
                    BiometricMethod.FACE_SAMSUNG -> permission.add("com.samsung.android.bio.face.permission.USE_FACE")
                    BiometricMethod.FACE_OPPO -> permission.add("oppo.permission.USE_FACE")
                    BiometricMethod.FINGERPRINT_API23, BiometricMethod.FINGERPRINT_SUPPORT -> permission.add(
                        "android.permission.USE_FINGERPRINT"
                    )
                    BiometricMethod.FINGERPRINT_FLYME -> permission.add("com.fingerprints.service.ACCESS_FINGERPRINT_MANAGER")
                    BiometricMethod.FINGERPRINT_SAMSUNG -> permission.add("com.samsung.android.providers.context.permission.WRITE_USE_APP_FEATURE_SURVEY")
                }
            }
            return ArrayList(permission)
        }

    private fun authenticateInternal(callback: AuthenticationCallback) {
        BiometricLoggerImpl.d("BiometricPromptCompat.authenticateInternal()")
        if (isActivityFinished(builder.getContext())) {
            BiometricLoggerImpl.e("Unable to start BiometricPromptCompat.authenticate() cause of Activity destroyed")
            callback.onCanceled()
            return
        }
        try {
            BiometricLoggerImpl.d("BiometricPromptCompat.authenticateInternal() - impl.authenticate")
            impl.authenticate(callback)
        } catch (ignore: IllegalStateException) {
            callback.onFailed(AuthenticationFailureReason.INTERNAL_ERROR)
        }
    }

    fun cancelAuthentication() {
        if (!API_ENABLED) {
            return
        }
        ExecutorHelper.startOnBackground {
            while (isDeviceInfoCheckInProgress || !isInit) {
                try {
                    Thread.sleep(250)
                } catch (ignore: InterruptedException) {
                }
            }
            ExecutorHelper.post {
                impl.cancelAuthentication()
            }
        }

    }

    fun cancelAuthenticationBecauseOnPause(): Boolean {
        if (!API_ENABLED) {
            return false
        }
        return if (!isInit) {
            ExecutorHelper.startOnBackground {
                while (isDeviceInfoCheckInProgress || !isInit) {
                    try {
                        Thread.sleep(250)
                    } catch (ignore: InterruptedException) {
                    }
                }
                ExecutorHelper.post {
                    impl.cancelAuthentication()
                }
            }
            true
        } else
            impl.cancelAuthenticationBecauseOnPause()
    }

    @ColorInt
    fun getDialogMainColor(): Int {
        if (!API_ENABLED)
            return ContextCompat.getColor(builder.getContext(), R.color.material_grey_50)
        return DialogMainColor.getColor(builder.getContext(), DarkLightThemes.isNightMode(builder.getContext()))
    }

    abstract class AuthenticationCallback {
        @MainThread
        open fun onSucceeded(confirmed: Set<BiometricType>){}

        @MainThread
        open fun onCanceled(){}

        @MainThread
        open fun onFailed(reason: AuthenticationFailureReason?){}

        @MainThread
        open fun onUIOpened(){}

        @MainThread
        open fun onUIClosed(){}
    }

    class Builder(
        private val biometricAuthRequest: BiometricAuthRequest,
        private val context: FragmentActivity
    ) {
        private val allAvailableTypes: HashSet<BiometricType> by lazy {
            val types = HashSet<BiometricType>()
            types.addAll(primaryAvailableTypes)
            types.addAll(secondaryAvailableTypes)
            types
        }
        private val primaryAvailableTypes: HashSet<BiometricType> by lazy {
            val types = HashSet<BiometricType>()
            val api =
                if (HardwareAccessImpl.getInstance(biometricAuthRequest).isNewBiometricApi) BiometricApi.BIOMETRIC_API else BiometricApi.LEGACY_API
            if (biometricAuthRequest.type == BiometricType.BIOMETRIC_ANY) {
                for (type in BiometricType.values()) {
                    if (type == BiometricType.BIOMETRIC_ANY)
                        continue
                    val request = BiometricAuthRequest(
                        api,
                        type
                    )
                    BiometricLoggerImpl.d(
                        "primaryAvailableTypes - $request -> ${
                            isHardwareDetected(
                                request
                            )
                        }"
                    )
                    if (BiometricManagerCompat.isBiometricReady(request)){
                        types.add(type)
                    }
                }
            } else {
                if (BiometricManagerCompat.isBiometricReady(biometricAuthRequest))
                    types.add(biometricAuthRequest.type)
            }
            types
        }
        private val secondaryAvailableTypes: HashSet<BiometricType> by lazy {
            val types = HashSet<BiometricType>()
            if (HardwareAccessImpl.getInstance(biometricAuthRequest).isNewBiometricApi) {
                if (biometricAuthRequest.type == BiometricType.BIOMETRIC_ANY) {
                    for (type in BiometricType.values()) {
                        if (type == BiometricType.BIOMETRIC_ANY)
                            continue
                        val request = BiometricAuthRequest(
                            BiometricApi.LEGACY_API,
                            type
                        )
                        BiometricLoggerImpl.d(
                            "secondaryAvailableTypes - $request -> ${
                                isHardwareDetected(
                                    request
                                )
                            }"
                        )
                        if (BiometricManagerCompat.isBiometricReady(request)) {
                            types.add(type)
                        }
                    }
                } else {
                    if (BiometricManagerCompat.isBiometricReady(biometricAuthRequest))
                        types.add(biometricAuthRequest.type)
                }
                types.removeAll(primaryAvailableTypes)
            }
            types
        }

        private var title: CharSequence? = null

        private var subtitle: CharSequence? = null

        private var description: CharSequence? = null

        private var negativeButtonText: CharSequence? = null

        private var negativeButtonListener: DialogInterface.OnClickListener? = null

        private lateinit var multiWindowSupport: MultiWindowSupport

        private var notificationEnabled = true

        @ColorInt
        private var colorNavBar: Int = Color.TRANSPARENT

        @ColorInt
        private var dividerColor: Int = Color.TRANSPARENT

        @ColorInt
        private var colorStatusBar: Int = Color.TRANSPARENT

        private var isTruncateChecked = false

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                this.colorNavBar = context.window.navigationBarColor
                this.colorStatusBar = context.window.statusBarColor
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dividerColor = context.window.navigationBarDividerColor
            }
            if (API_ENABLED) {
                multiWindowSupport = MultiWindowSupport(context)
            }
        }

        constructor(context: FragmentActivity) : this(
            BiometricAuthRequest(
                BiometricApi.AUTO,
                BiometricType.BIOMETRIC_ANY
            ), context
        ) {
        }

        fun getTitle(): CharSequence? {
            return title
        }

        fun getSubtitle(): CharSequence? {
            return subtitle
        }

        fun getDescription(): CharSequence? {
            return description
        }

        fun getNegativeButtonText(): CharSequence? {
            return negativeButtonText
        }

        fun getNegativeButtonListener(): DialogInterface.OnClickListener? {
            return negativeButtonListener
        }

        fun getNavBarColor(): Int {
            return colorNavBar
        }

        fun getStatusBarColor(): Int {
            return colorStatusBar
        }

        fun getDividerColor(): Int {
            return dividerColor
        }

        fun isNotificationEnabled(): Boolean {
            return notificationEnabled
        }

        fun isTruncateChecked(): Boolean {
            return isTruncateChecked
        }

        fun getPrimaryAvailableTypes(): Set<BiometricType> {
            return HashSet<BiometricType>(primaryAvailableTypes)
        }

        fun getSecondaryAvailableTypes(): Set<BiometricType> {
            return HashSet<BiometricType>(secondaryAvailableTypes)
        }

        fun getAllAvailableTypes(): Set<BiometricType> {
            return HashSet<BiometricType>(allAvailableTypes)
        }

        fun getContext(): FragmentActivity {
            return context
        }

        fun getBiometricAuthRequest(): BiometricAuthRequest {
            return biometricAuthRequest
        }

        fun getMultiWindowSupport(): MultiWindowSupport {
            return multiWindowSupport
        }

        fun setEnabledNotification(enabled: Boolean): Builder {
            this.notificationEnabled = enabled
            return this
        }

        fun setTitle(title: CharSequence?): Builder {
            this.title = title
            return this
        }

        fun setTitle(@StringRes titleRes: Int): Builder {
            title = context.getString(titleRes)
            return this
        }

        fun setSubtitle(subtitle: CharSequence?): Builder {
            this.subtitle = subtitle
            return this
        }

        fun setSubtitle(@StringRes subtitleRes: Int): Builder {
            subtitle = context.getString(subtitleRes)
            return this
        }

        fun setDescription(description: CharSequence?): Builder {
            this.description = description
            return this
        }

        fun setDescription(@StringRes descriptionRes: Int): Builder {
            description = context.getString(descriptionRes)
            return this
        }

        fun setNegativeButtonText(text: CharSequence): Builder {
            negativeButtonText = text
            return this
        }
        fun setNegativeButtonText( @StringRes res: Int): Builder {
            negativeButtonText = context.getString(res)
            return this
        }
        fun setNegativeButton(
            text: CharSequence,
            listener: DialogInterface.OnClickListener?
        ): Builder {
            negativeButtonText = text
            negativeButtonListener = listener
            return this
        }

        fun setNegativeButton(
            @StringRes textResId: Int,
            listener: DialogInterface.OnClickListener?
        ): Builder {
            negativeButtonText = context.getString(textResId)
            negativeButtonListener = listener
            return this
        }

        fun build(): BiometricPromptCompat {
            if (title == null)
                title = BiometricTitle.getRelevantTitle(context, getAllAvailableTypes())
            if (negativeButtonText == null)
                negativeButtonText = context.getString(android.R.string.cancel)
            TruncatedTextFix.recalculateTexts(this, object : TruncatedTextFix.OnTruncateChecked {
                override fun onDone() {
                    isTruncateChecked = true
                }
            })
            return BiometricPromptCompat(this)
        }
    }
}