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

package dev.skomlach.biometric.compat.impl

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.annotation.ColorInt
import androidx.biometric.BiometricFragment
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.biometric.CancellationHelper
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import dev.skomlach.biometric.compat.*
import dev.skomlach.biometric.compat.crypto.BiometricCryptoException
import dev.skomlach.biometric.compat.crypto.BiometricCryptoObjectHelper
import dev.skomlach.biometric.compat.engine.BiometricAuthentication
import dev.skomlach.biometric.compat.engine.BiometricAuthenticationListener
import dev.skomlach.biometric.compat.engine.core.RestartPredicatesImpl.defaultPredicate
import dev.skomlach.biometric.compat.impl.dialogs.BiometricPromptCompatDialogImpl
import dev.skomlach.biometric.compat.utils.BiometricErrorLockoutPermanentFix
import dev.skomlach.biometric.compat.utils.DevicesWithKnownBugs
import dev.skomlach.biometric.compat.utils.DevicesWithKnownBugs.isOnePlusWithBiometricBug
import dev.skomlach.biometric.compat.utils.HardwareAccessImpl
import dev.skomlach.biometric.compat.utils.Vibro
import dev.skomlach.biometric.compat.utils.activityView.IconStateHelper
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.d
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.e
import dev.skomlach.biometric.compat.utils.monet.SystemColorScheme
import dev.skomlach.biometric.compat.utils.monet.toArgb
import dev.skomlach.biometric.compat.utils.notification.BiometricNotificationManager
import dev.skomlach.biometric.compat.utils.themes.DarkLightThemes
import dev.skomlach.common.misc.ExecutorHelper
import dev.skomlach.common.misc.Utils
import dev.skomlach.common.misc.Utils.isAtLeastR
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@TargetApi(Build.VERSION_CODES.P)

class BiometricPromptApi28Impl(override val builder: BiometricPromptCompat.Builder) :
    IBiometricPromptImpl, AuthCallback {
    private val biometricPromptInfo: PromptInfo
    private val biometricPrompt: BiometricPrompt
    private var restartPredicate = defaultPredicate()
    private var dialog: BiometricPromptCompatDialogImpl? = null
    private var callback: BiometricPromptCompat.AuthenticationCallback? = null
    private val authFinished: MutableMap<BiometricType?, AuthResult> =
        HashMap<BiometricType?, AuthResult>()
    private var biometricFragment: AtomicReference<BiometricFragment?> =
        AtomicReference<BiometricFragment?>(null)
    private val fmAuthCallback: BiometricAuthenticationListener =
        BiometricAuthenticationCallbackImpl()
    val authCallback: BiometricPrompt.AuthenticationCallback =
        object : BiometricPrompt.AuthenticationCallback() {
            private var errorTs = System.currentTimeMillis()
            private val skipTimeout =
                builder.getContext().resources.getInteger(android.R.integer.config_shortAnimTime)

            //https://forums.oneplus.com/threads/oneplus-7-pro-fingerprint-biometricprompt-does-not-show.1035821/
            private var onePlusWithBiometricBugFailure = false
            override fun onAuthenticationFailed() {
                d("BiometricPromptApi28Impl.onAuthenticationFailed")
                val tmp = System.currentTimeMillis()
                if (tmp - errorTs <= skipTimeout)
                    return
                errorTs = tmp
                if (isOnePlusWithBiometricBug) {
                    onePlusWithBiometricBugFailure = true
                    cancelAuthentication()
                } else {
                    //...normal failed processing...//
                    for (module in (if (isNativeBiometricWorkaroundRequired) builder.getAllAvailableTypes() else builder.getPrimaryAvailableTypes())) {
                        IconStateHelper.errorType(module)
                    }
                    dialog?.onFailure(false)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                d("BiometricPromptApi28Impl.onAuthenticationError: $errorCode $errString")
                val tmp = System.currentTimeMillis()
                if (tmp - errorTs <= skipTimeout)
                    return
                errorTs = tmp
                // Authentication failed on OnePlus device with broken BiometricPrompt implementation
                // Present the same screen with additional buttons to allow retry/fail
                if (onePlusWithBiometricBugFailure) {
                    onePlusWithBiometricBugFailure = false
                    //...present retryable error screen...
                    return
                }
                //...present normal failed screen...

                ExecutorHelper.post(Runnable {
                    var failureReason = AuthenticationFailureReason.UNKNOWN
                    when (errorCode) {
                        BiometricPrompt.ERROR_NO_BIOMETRICS -> failureReason =
                            AuthenticationFailureReason.NO_BIOMETRICS_REGISTERED
                        BiometricPrompt.ERROR_HW_NOT_PRESENT -> failureReason =
                            AuthenticationFailureReason.NO_HARDWARE
                        BiometricPrompt.ERROR_HW_UNAVAILABLE -> failureReason =
                            AuthenticationFailureReason.HARDWARE_UNAVAILABLE
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> {
                            for (t in builder.getPrimaryAvailableTypes()) {
                                BiometricErrorLockoutPermanentFix.setBiometricSensorPermanentlyLocked(
                                    t
                                )
                            }
                            failureReason = AuthenticationFailureReason.HARDWARE_UNAVAILABLE
                        }
                        BiometricPrompt.ERROR_UNABLE_TO_PROCESS -> failureReason =
                            AuthenticationFailureReason.HARDWARE_UNAVAILABLE
                        BiometricPrompt.ERROR_NO_SPACE -> failureReason =
                            AuthenticationFailureReason.SENSOR_FAILED
                        BiometricPrompt.ERROR_TIMEOUT -> failureReason =
                            AuthenticationFailureReason.TIMEOUT
                        BiometricPrompt.ERROR_LOCKOUT -> {
                            HardwareAccessImpl.getInstance(builder.getBiometricAuthRequest())
                                .lockout()
                            failureReason = AuthenticationFailureReason.LOCKED_OUT
                        }
                        else -> {
                            cancelAuth()
                            cancelAuthentication()
                            return@Runnable
                        }
                    }
                    if (restartPredicate.invoke(failureReason)) {
                        if (callback != null) {
                            for (module in (if (isNativeBiometricWorkaroundRequired) builder.getAllAvailableTypes() else builder.getPrimaryAvailableTypes())) {
                                IconStateHelper.errorType(module)
                            }
                            dialog?.onFailure(
                                failureReason == AuthenticationFailureReason.LOCKED_OUT
                            )
                            authenticate(callback)
                        }
                    } else {
                        checkAuthResultForPrimary(
                            AuthResult.AuthResultState.FATAL_ERROR,
                            null,
                            failureReason
                        )
                    }
                })
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                d("BiometricPromptApi28Impl.onAuthenticationSucceeded: ${result.authenticationType}; Crypto=${result.cryptoObject}")
                val tmp = System.currentTimeMillis()
                if (tmp - errorTs <= skipTimeout)
                    return
                errorTs = tmp
                onePlusWithBiometricBugFailure = false
                checkAuthResultForPrimary(AuthResult.AuthResultState.SUCCESS, result.cryptoObject)
            }
        }
    private val isFingerprint = AtomicBoolean(false)

    init {
        val promptInfoBuilder = PromptInfo.Builder()
        builder.getTitle()?.let {
            promptInfoBuilder.setTitle(it)
        }

        builder.getSubtitle()?.let {
            promptInfoBuilder.setSubtitle(it)
        }

        builder.getDescription()?.let {
            promptInfoBuilder.setDescription(it)
        }


        val systemInScreenDialogMustBeUsed =
            isFingerprint.get() && DevicesWithKnownBugs.isHideDialogInstantly
        if (!systemInScreenDialogMustBeUsed) {
            var buttonTextColor: Int =
                ContextCompat.getColor(
                    builder.getContext(),
                    if (Utils.isAtLeastS) R.color.material_blue_500 else R.color.material_deep_teal_500
                )

            if (Utils.isAtLeastS) {
                val monetColors = SystemColorScheme()
                if (DarkLightThemes.isNightModeCompatWithInscreen(builder.getContext()))
                    monetColors.accent2[100]?.toArgb()?.let {
                        buttonTextColor = it
                    }
                else
                    monetColors.neutral2[500]?.toArgb()?.let {
                        buttonTextColor = it
                    }
            }
            builder.getContext().getString(android.R.string.cancel).let {
                if (isAtLeastR) promptInfoBuilder.setNegativeButtonText(it) else promptInfoBuilder.setNegativeButtonText(
                    getFixedString(
                        it, color = buttonTextColor
                    )
                )
            }
        }

        promptInfoBuilder.setDeviceCredentialAllowed(false)
        promptInfoBuilder.setAllowedAuthenticators(
            if (builder.getCryptographyPurpose() != null)
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            else
                (BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.BIOMETRIC_STRONG)
        )

        promptInfoBuilder.setConfirmationRequired(false)
        biometricPromptInfo = promptInfoBuilder.build()
        biometricPrompt = BiometricPrompt(
            builder.getContext(),
            ExecutorHelper.executor, authCallback
        )
        isFingerprint.set(
            builder.getPrimaryAvailableTypes().contains(BiometricType.BIOMETRIC_FINGERPRINT)
        )
    }

    private fun getFixedString(str: CharSequence?, @ColorInt color: Int): CharSequence {
        val wordtoSpan: Spannable = SpannableString(str)
        wordtoSpan.setSpan(
            ForegroundColorSpan(color),
            0,
            wordtoSpan.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return wordtoSpan
    }

    override fun authenticate(cbk: BiometricPromptCompat.AuthenticationCallback?) {
        d("BiometricPromptApi28Impl.authenticate():")
        this.restartPredicate = defaultPredicate()
        this.authFinished.clear()
        this.biometricFragment.set(null)
        callback = cbk
        onUiOpened()
        if (DevicesWithKnownBugs.isMissedBiometricUI) {
            //1) LG G8 do not have BiometricPrompt UI
            //2) One Plus 6T with InScreen fingerprint sensor
            dialog = BiometricPromptCompatDialogImpl(
                builder,
                this@BiometricPromptApi28Impl,
                isFingerprint.get() && DevicesWithKnownBugs.hasUnderDisplayFingerprint
            )
            dialog?.showDialog()
        } else {
            startAuth()
        }

    }

    override fun cancelAuthentication() {
        d("BiometricPromptApi28Impl.cancelAuthentication():")
        if (dialog != null) dialog?.dismissDialog() else {
            stopAuth()
        }
        onUiClosed()
    }

    private fun hasPrimaryFinished(): Boolean {

        val authFinishedList: List<BiometricType?> = ArrayList(authFinished.keys)
        val allList: MutableList<BiometricType?> = ArrayList(
            builder.getPrimaryAvailableTypes()
        )
        allList.removeAll(authFinishedList)
        return allList.isEmpty()
    }

    private fun hasSecondaryFinished(): Boolean {

        val authFinishedList: List<BiometricType?> = ArrayList(authFinished.keys)
        val allList: MutableList<BiometricType?> = ArrayList(
            builder.getSecondaryAvailableTypes()
        )
        allList.removeAll(authFinishedList)
        return allList.isEmpty()
    }

    override fun startAuth() {
        d("BiometricPromptApi28Impl.startAuth():")

        val withoutFingerprint = builder.getAllAvailableTypes().filter {
            it != BiometricType.BIOMETRIC_ANY
        }.filter {
            it != BiometricType.BIOMETRIC_FINGERPRINT
        }

        if (!isNativeBiometricWorkaroundRequired) {
            if (!hasSecondaryFinished()) {
                val secondary = HashSet<BiometricType>(builder.getSecondaryAvailableTypes())
                if (secondary.isNotEmpty()) {
                    BiometricAuthentication.authenticate(
                        builder.getCryptographyPurpose(),
                        null,
                        ArrayList<BiometricType>(secondary),
                        fmAuthCallback,
                        BundleBuilder.create(builder)
                    )
                }
            }

            if (!hasPrimaryFinished()) {
                showSystemUi(biometricPrompt)
            }
        } else {
            val delayMillis = 1500L
            val shortDelayMillis =
                builder.getContext().resources.getInteger(android.R.integer.config_longAnimTime)
                    .toLong()
            val successList = mutableSetOf<AuthenticationResult>()
            BiometricAuthentication.authenticate(
                builder.getCryptographyPurpose(),
                null,
                ArrayList<BiometricType>(withoutFingerprint),
                object : BiometricAuthenticationListener {
                    override fun onSuccess(module: AuthenticationResult?) {
                        module?.let {
                            successList.add(it)
                        }
                    }

                    override fun onHelp(msg: CharSequence?) {
                    }

                    override fun onFailure(
                        failureReason: AuthenticationFailureReason?,
                        module: BiometricType?
                    ) {
                    }

                    override fun onCanceled(module: BiometricType?) {}
                },
                BundleBuilder.create(builder)
            )
            val finalTaskExecuted = AtomicBoolean(false)
            val finalTask = Runnable {
                finalTaskExecuted.set(true)
                //Flow for Samsung devices
                BiometricAuthentication.cancelAuthentication()

                if (successList.isNotEmpty()) {
                    ExecutorHelper.postDelayed({
                        val dialogClosed = AtomicBoolean(false)
                        showSystemUi(BiometricPrompt(
                            builder.getContext(),
                            ExecutorHelper.executor,
                            object : BiometricPrompt.AuthenticationCallback() {
                                private var errorTs = System.currentTimeMillis()
                                private val skipTimeout =
                                    builder.getContext().resources.getInteger(android.R.integer.config_shortAnimTime)
                                var onePlusWithBiometricBugFailure = false
                                override fun onAuthenticationFailed() {
                                    val tmp = System.currentTimeMillis()
                                    if (tmp - errorTs <= skipTimeout)
                                        return
                                    errorTs = tmp
                                    if (isOnePlusWithBiometricBug) {
                                        onePlusWithBiometricBugFailure = true
                                        if (!dialogClosed.get()) {
                                            dialogClosed.set(true)
                                            stopAuth()
                                            callback?.onSucceeded(successList)
                                        }
                                    }
                                }

                                override fun onAuthenticationError(
                                    errorCode: Int,
                                    errString: CharSequence
                                ) {
                                    val tmp = System.currentTimeMillis()
                                    if (tmp - errorTs <= skipTimeout)
                                        return
                                    errorTs = tmp
                                    if (onePlusWithBiometricBugFailure) {
                                        onePlusWithBiometricBugFailure = false
                                        return
                                    }
                                    if (!dialogClosed.get()) {
                                        dialogClosed.set(true)
                                        stopAuth()
                                        callback?.onSucceeded(successList)
                                    }
                                }

                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                    val tmp = System.currentTimeMillis()
                                    if (tmp - errorTs <= skipTimeout)
                                        return
                                    errorTs = tmp
                                    if (!dialogClosed.get()) {
                                        dialogClosed.set(true)
                                        stopAuth()
                                        callback?.onSucceeded(successList)
                                    }
                                }
                            }
                        )
                        )
                        ExecutorHelper.postDelayed({
                            if (!dialogClosed.get()) {
                                dialogClosed.set(true)
                                stopAuth()
                                callback?.onSucceeded(successList)
                            }
                        }, delayMillis)
                    }, shortDelayMillis)
                } else
                //general case
                {
                    if (!hasPrimaryFinished()) {
                        showSystemUi(biometricPrompt)
                    }
                }

            }
            val resultCheckTask = object : Runnable {
                override fun run() {
                    if (!finalTaskExecuted.get()) {
                        if (successList.isNotEmpty()) {
                            ExecutorHelper.removeCallbacks(finalTask)
                            finalTask.run()
                        } else {
                            ExecutorHelper.postDelayed(this, shortDelayMillis)
                        }
                    }
                }
            }
            ExecutorHelper.postDelayed(finalTask, delayMillis)
            ExecutorHelper.postDelayed(resultCheckTask, shortDelayMillis)
        }
    }

    private fun showSystemUi(biometricPrompt: BiometricPrompt) {
        try {

            var biometricCryptoObject: BiometricCryptoObject? = null
            builder.getCryptographyPurpose()?.let {
                try {
                    biometricCryptoObject = BiometricCryptoObjectHelper.getBiometricCryptoObject(
                        "BiometricPromptCompat",
                        builder.getCryptographyPurpose(),
                        true
                    )
                } catch (e: BiometricCryptoException) {
                    if (builder.getCryptographyPurpose()?.purpose == BiometricCryptographyPurpose.ENCRYPT) {
                        BiometricCryptoObjectHelper.deleteCrypto("BiometricPromptCompat")
                        biometricCryptoObject =
                            BiometricCryptoObjectHelper.getBiometricCryptoObject(
                                "BiometricPromptCompat",
                                builder.getCryptographyPurpose(),
                                true
                            )
                    } else throw e
                }
            }

            val crpObject =
                if (biometricCryptoObject?.cipher != null)
                    biometricCryptoObject?.cipher?.let { BiometricPrompt.CryptoObject(it) }
                else if (biometricCryptoObject?.mac != null)
                    biometricCryptoObject?.mac?.let { BiometricPrompt.CryptoObject(it) }
                else if (biometricCryptoObject?.signature != null)
                    biometricCryptoObject?.signature?.let { BiometricPrompt.CryptoObject(it) }
                else
                    null

            d("BiometricPromptCompat.authenticate:  Crypto=$crpObject")
            if (crpObject != null) {
                try {
                    biometricPrompt.authenticate(biometricPromptInfo, crpObject)
                } catch (e: Throwable) {
                    biometricPrompt.authenticate(biometricPromptInfo)
                }
            } else {
                biometricPrompt.authenticate(biometricPromptInfo)
            }
            ExecutorHelper.startOnBackground {
                //fallback - sometimes we not able to cancel BiometricPrompt properly
                try {
                    val m = BiometricPrompt::class.java.declaredMethods.first {
                        it.parameterTypes.size == 1 && it.parameterTypes[0] == FragmentManager::class.java && it.returnType == BiometricFragment::class.java
                    }
                    val isAccessible = m.isAccessible
                    try {
                        if (!isAccessible)
                            m.isAccessible = true
                        biometricFragment.set(
                            m.invoke(
                                null,
                                builder.getContext().supportFragmentManager
                            ) as BiometricFragment?
                        )
                    } finally {
                        if (!isAccessible)
                            m.isAccessible = false
                    }
                } catch (e: Throwable) {
                    e(e)
                }
            }
        } catch (e: BiometricCryptoException) {
            BiometricLoggerImpl.e(e)
            checkAuthResultForPrimary(
                AuthResult.AuthResultState.FATAL_ERROR,
                null,
                AuthenticationFailureReason.CRYPTO_ERROR
            )
        }
    }


    override fun stopAuth() {
        d("BiometricPromptApi28Impl.stopAuth():")
        BiometricAuthentication.cancelAuthentication()
        biometricFragment.get()?.let {
            CancellationHelper.forceCancel(it)
        } ?: run {
            biometricPrompt.cancelAuthentication()
        }
        biometricFragment.set(null)
    }

    override fun cancelAuth() {
        val success =
            authFinished.values.lastOrNull { it.authResultState == AuthResult.AuthResultState.SUCCESS }
        if(success!=null)
            return
        callback?.onCanceled()
    }

    override fun onUiOpened() {
        callback?.onUIOpened()
    }

    override fun onUiClosed() {
        callback?.onUIClosed()
    }

    private val isNativeBiometricWorkaroundRequired: Boolean
        get() {
            val candidates = builder.getAllAvailableTypes().filter {
                it != BiometricType.BIOMETRIC_ANY
            }
            return DevicesWithKnownBugs.systemDealWithBiometricPrompt && candidates.size > 1 //If only one - let the system to deal with this
        }

    private fun checkAuthResultForPrimary(
        authResult: AuthResult.AuthResultState,
        cryptoObject: BiometricPrompt.CryptoObject?,
        reason: AuthenticationFailureReason? = null
    ) {
        var failureReason = reason
        if (mutableListOf(
                AuthenticationFailureReason.SENSOR_FAILED,
                AuthenticationFailureReason.AUTHENTICATION_FAILED
            ).contains(failureReason)
        ) {
            HardwareAccessImpl.getInstance(builder.getBiometricAuthRequest()).lockout()
            failureReason = AuthenticationFailureReason.LOCKED_OUT
        }
        var added = false
        val crypto = if (cryptoObject == null) null else {
            BiometricCryptoObject(cryptoObject.signature, cryptoObject.cipher, cryptoObject.mac)
        }
        for (module in (if (isNativeBiometricWorkaroundRequired) builder.getAllAvailableTypes() else builder.getPrimaryAvailableTypes())) {
            authFinished[module] =
                AuthResult(authResult, AuthenticationResult(module, crypto), failureReason)
            dialog?.authFinishedCopy = authFinished
            if (AuthResult.AuthResultState.SUCCESS == authResult) {
                IconStateHelper.successType(module)
            } else
                IconStateHelper.errorType(module)
            added = true
            BiometricNotificationManager.dismiss(module)
        }
        if (added && builder.getBiometricAuthRequest().confirmation == BiometricConfirmation.ALL && AuthResult.AuthResultState.SUCCESS == authResult) {
            Vibro.start()
        }

        val authFinishedList: List<BiometricType?> = java.util.ArrayList(authFinished.keys)
        val allList: MutableList<BiometricType?> = java.util.ArrayList(
            builder.getAllAvailableTypes()
        )
        allList.removeAll(authFinishedList)
        d("checkAuthResultForPrimary.authFinished - ${builder.getBiometricAuthRequest()}: $allList; ($authFinished / ${builder.getAllAvailableTypes()})")
        val error =
            authFinished.values.lastOrNull { it.authResultState == AuthResult.AuthResultState.FATAL_ERROR }
        val success =
            authFinished.values.lastOrNull { it.authResultState == AuthResult.AuthResultState.SUCCESS }
        d("checkAuthResultForPrimary.authFinished - ${builder.getBiometricAuthRequest()}: $error/$success")
        if (((success != null || allList.isEmpty()) && builder.getBiometricAuthRequest().confirmation == BiometricConfirmation.ANY) ||
            (builder.getBiometricAuthRequest().confirmation == BiometricConfirmation.ALL && (DevicesWithKnownBugs.systemDealWithBiometricPrompt || allList.isEmpty()))
        ) {
            ExecutorHelper.post {
                cancelAuthentication()
                if (success != null) {
                    val onlySuccess = authFinished.filter {
                        it.value.authResultState == AuthResult.AuthResultState.SUCCESS
                    }
                    val fixCryptoObjects = builder.getCryptographyPurpose()?.purpose == null
                    callback?.onSucceeded(onlySuccess.keys.toList().mapNotNull {
                        var result: AuthenticationResult? = null
                        onlySuccess[it]?.successData?.let { r ->
                            result = AuthenticationResult(
                                r.confirmed,
                                if (fixCryptoObjects) null else r.cryptoObject
                            )
                        }
                        result
                    }.toSet())
                } else if (error != null) {
                    if (failureReason == AuthenticationFailureReason.LOCKED_OUT) {
                        ExecutorHelper.postDelayed({
                            callback?.onFailed(error.failureReason)
                        }, 2000)
                    } else {
                        callback?.onFailed(error.failureReason)
                    }
                }
            }
        } else if (allList.isNotEmpty()) {
            if (dialog == null) {
                dialog =
                    BiometricPromptCompatDialogImpl(
                        builder, object : AuthCallback {
                            private val ignoreFirstOpen = AtomicBoolean(true)
                            override fun startAuth() {
                                if (ignoreFirstOpen.getAndSet(false))
                                    return
                                this@BiometricPromptApi28Impl.startAuth()
                            }

                            override fun stopAuth() {
                                this@BiometricPromptApi28Impl.stopAuth()
                            }

                            override fun cancelAuth() {
                                this@BiometricPromptApi28Impl.cancelAuth()
                            }

                            override fun onUiOpened() {
                                this@BiometricPromptApi28Impl.onUiOpened()
                            }

                            override fun onUiClosed() {
                                this@BiometricPromptApi28Impl.onUiClosed()
                            }
                        },
                        builder.getSecondaryAvailableTypes()
                            .contains(BiometricType.BIOMETRIC_FINGERPRINT)
                                && DevicesWithKnownBugs.hasUnderDisplayFingerprint
                    )
                dialog?.authFinishedCopy = authFinished
            }
            dialog?.showDialog()
        }
    }

    private fun checkAuthResultForSecondary(
        module: AuthenticationResult?,
        authResult: AuthResult.AuthResultState,
        failureReason: AuthenticationFailureReason? = null
    ) {
        if (authResult == AuthResult.AuthResultState.SUCCESS) {
            IconStateHelper.successType(module?.confirmed)
            if (builder.getBiometricAuthRequest().confirmation == BiometricConfirmation.ALL) {
                Vibro.start()
            }
        } else if (authResult == AuthResult.AuthResultState.FATAL_ERROR) {
            IconStateHelper.errorType(module?.confirmed)
            dialog?.onFailure(failureReason == AuthenticationFailureReason.LOCKED_OUT)
        }
        //non fatal
        if (mutableListOf(
                AuthenticationFailureReason.SENSOR_FAILED,
                AuthenticationFailureReason.AUTHENTICATION_FAILED
            ).contains(failureReason)
        ) {
            return
        }
        authFinished[module?.confirmed] = AuthResult(authResult, module, failureReason)
        dialog?.authFinishedCopy = authFinished
        BiometricNotificationManager.dismiss(module?.confirmed)

        val authFinishedList: List<BiometricType?> = java.util.ArrayList(authFinished.keys)
        val allList: MutableList<BiometricType?> = java.util.ArrayList(
            builder.getAllAvailableTypes()
        )
        allList.removeAll(authFinishedList)
        d("checkAuthResultForSecondary.authFinished - ${builder.getBiometricAuthRequest()}: $allList; ($authFinished / ${builder.getAllAvailableTypes()})")
        val error =
            authFinished.values.lastOrNull { it.authResultState == AuthResult.AuthResultState.FATAL_ERROR }
        val success =
            authFinished.values.lastOrNull { it.authResultState == AuthResult.AuthResultState.SUCCESS }

        d("checkAuthResultForSecondary.authFinished - ${builder.getBiometricAuthRequest()}: $error/$success")
        if (((success != null || allList.isEmpty()) && builder.getBiometricAuthRequest().confirmation == BiometricConfirmation.ANY) ||
            (builder.getBiometricAuthRequest().confirmation == BiometricConfirmation.ALL && allList.isEmpty())
        ) {
            ExecutorHelper.post {
                cancelAuthentication()
                if (success != null) {
                    val onlySuccess = authFinished.filter {
                        it.value.authResultState == AuthResult.AuthResultState.SUCCESS
                    }
                    val fixCryptoObjects = builder.getCryptographyPurpose()?.purpose == null
                    callback?.onSucceeded(onlySuccess.keys.toList().mapNotNull {
                        var result: AuthenticationResult? = null
                        onlySuccess[it]?.successData?.let { r ->
                            result = AuthenticationResult(
                                r.confirmed,
                                if (fixCryptoObjects) null else r.cryptoObject
                            )
                        }
                        result
                    }.toSet())
                } else if (error != null) {
                    if (error.failureReason !== AuthenticationFailureReason.LOCKED_OUT) {
                        callback?.onFailed(error.failureReason)
                    } else {
                        HardwareAccessImpl.getInstance(builder.getBiometricAuthRequest()).lockout()
                        ExecutorHelper.postDelayed({
                            callback?.onFailed(error.failureReason)
                        }, 2000)
                    }
                }
            }
        }
    }

    private inner class BiometricAuthenticationCallbackImpl : BiometricAuthenticationListener {

        override fun onSuccess(module: AuthenticationResult?) {
            checkAuthResultForSecondary(module, AuthResult.AuthResultState.SUCCESS)
        }

        override fun onHelp(msg: CharSequence?) {
            if (!msg.isNullOrEmpty()) {
                if (dialog != null) dialog?.onHelp(msg)
            }
        }

        override fun onFailure(
            failureReason: AuthenticationFailureReason?,
            module: BiometricType?
        ) {
            checkAuthResultForSecondary(
                AuthenticationResult(confirmed = module),
                AuthResult.AuthResultState.FATAL_ERROR,
                failureReason
            )
        }

        override fun onCanceled(module: BiometricType?) {
            cancelAuth()
            cancelAuthentication()
        }
    }
}