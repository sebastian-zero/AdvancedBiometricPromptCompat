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

package dev.skomlach.biometric.compat.utils.hardware

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import dev.skomlach.biometric.compat.BiometricAuthRequest
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.e
import dev.skomlach.common.contextprovider.AndroidContext
import dev.skomlach.common.misc.Utils.isAtLeastR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


@TargetApi(Build.VERSION_CODES.Q)

class Android29Hardware(authRequest: BiometricAuthRequest) : Android28Hardware(authRequest) {
    companion object {
        private val appContext = AndroidContext.appContext

        private var cachedCanAuthenticateValue =
            AtomicInteger(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
        private var job: Job? = null
        private var checkStartedTs = 0L
        private val backgroundThreadExecutor: ExecutorService = Executors.newCachedThreadPool()
        private var backgroundScope =
            CoroutineScope(backgroundThreadExecutor.asCoroutineDispatcher())

        private fun canAuthenticate(): Int {

            if (job?.isActive == true) {
                if (System.currentTimeMillis() - checkStartedTs >= TimeUnit.SECONDS.toMillis(5)) {
                    job?.cancel()
                    job = null
                }
            }
            if (job == null || job?.isCompleted == true) {
                checkStartedTs = System.currentTimeMillis()
                val isFinished = AtomicBoolean(false)
                job = backgroundScope.launch {
                    isFinished.set(false)
                    updateCodeSync()
                    isFinished.set(true)
                }
                while (!isFinished.get() && (System.currentTimeMillis() - checkStartedTs <= 5)) {
                    Thread.sleep(1)
                }
            }
            return cachedCanAuthenticateValue.get()
        }

        @SuppressLint("WrongConstant")
        private fun updateCodeSync() {
            var code = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
            try {
                var biometricManager: android.hardware.biometrics.BiometricManager? =
                    appContext.getSystemService(
                        android.hardware.biometrics.BiometricManager::class.java
                    )

                if (biometricManager == null) {
                    biometricManager = appContext.getSystemService(
                        Context.BIOMETRIC_SERVICE
                    ) as android.hardware.biometrics.BiometricManager?
                }
                if (biometricManager != null) {
                    code = if (isAtLeastR) {
                        val authenticators = arrayOf(
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                                    or android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG,
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK,
                            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
                        )
                        var canAuthenticate = biometricManager.canAuthenticate()
                        for (authenticator in authenticators) {
                            canAuthenticate = biometricManager.canAuthenticate(authenticator)
                            if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                                break
                            }
                        }
                        canAuthenticate
                    } else {
                        biometricManager.canAuthenticate()
                    }
                }
            } catch (e: Throwable) {
                e(e)
            } finally {
                e("Android29Hardware - canAuthenticate=$code")
                cachedCanAuthenticateValue.set(code)
            }
        }
    }

    override val isAnyHardwareAvailable: Boolean
        get() {
            val canAuthenticate = canAuthenticate()
            return if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                true
            } else {
                canAuthenticate != BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE && canAuthenticate != BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
            }
        }
    override val isAnyBiometricEnrolled: Boolean
        get() {
            val canAuthenticate = canAuthenticate()
            return if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
                true
            } else {
                canAuthenticate != BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED && canAuthenticate != BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED
            }
        }
}