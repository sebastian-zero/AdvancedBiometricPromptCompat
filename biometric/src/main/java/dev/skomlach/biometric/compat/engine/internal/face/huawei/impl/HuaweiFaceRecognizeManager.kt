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

package dev.skomlach.biometric.compat.engine.internal.face.huawei.impl

import android.content.Context
import com.huawei.facerecognition.FaceRecognizeManager
import com.huawei.facerecognition.FaceRecognizeManager.FaceRecognizeCallback
import dev.skomlach.biometric.compat.engine.BiometricCodes
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.d
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl.e
import java.util.concurrent.locks.ReentrantLock


class HuaweiFaceRecognizeManager(context: Context?) {

    companion object {
        const val DEFAULT_FLAG = 1
        const val CODE_CALLBACK_ACQUIRE = 3
        const val CODE_CALLBACK_BUSY = 4
        const val CODE_CALLBACK_CANCEL = 2
        const val CODE_CALLBACK_OUT_OF_MEM = 5
        const val CODE_CALLBACK_RESULT = 1
        const val HUAWEI_FACE_AUTHENTICATOR_FAIL = 103
        const val HUAWEI_FACE_AUTHENTICATOR_SUCCESS = 100

        //
        //    public static final int HUAWEI_FACE_AUTH_ERROR_CANCEL = 102;
        //    public static final int HUAWEI_FACE_AUTH_ERROR_LOCKED = 129;
        //    public static final int HUAWEI_FACE_AUTH_ERROR_TIMEOUT = 113;
        const val HUAWEI_FACE_AUTH_STATUS_BRIGHT = 406
        const val HUAWEI_FACE_AUTH_STATUS_DARK = 405
        const val HUAWEI_FACE_AUTH_STATUS_EYE_CLOSED = 403
        const val HUAWEI_FACE_AUTH_STATUS_FACE_OFFET_BOTTOM = 412
        const val HUAWEI_FACE_AUTH_STATUS_FACE_OFFET_LEFT = 409
        const val HUAWEI_FACE_AUTH_STATUS_FACE_OFFET_RIGHT = 410
        const val HUAWEI_FACE_AUTH_STATUS_FACE_OFFET_TOP = 411
        const val HUAWEI_FACE_AUTH_STATUS_FAR_FACE = 404
        const val HUAWEI_FACE_AUTH_STATUS_INSUFFICIENT = 402
        const val HUAWEI_FACE_AUTH_STATUS_MOUTH_OCCLUSION = 408
        const val HUAWEI_FACE_AUTH_STATUS_PARTIAL = 401
        const val HUAWEI_FACE_AUTH_STATUS_QUALITY = 407
        const val TAG = "HuaweiFaceRecognize"
        const val TYPE_CALLBACK_AUTH = 2
        var instance: HuaweiFaceRecognizeManager? = null
            private set
        var fRManager: FaceRecognizeManager? = null
            private set

        fun converHwAcquireInfoToHuawei(hwAcquireInfo: Int): Int {
            val str = TAG
            val stringBuilder = StringBuilder()
            stringBuilder.append(" converHwhwAcquireInfoToHuawei hwAcquireInfo is ")
            stringBuilder.append(hwAcquireInfo)
            e(str, stringBuilder.toString())
            return when (hwAcquireInfo) {
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_OK -> HUAWEI_FACE_AUTHENTICATOR_SUCCESS
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_EYE_CLOSE -> HUAWEI_FACE_AUTH_STATUS_EYE_CLOSED
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_BAD_QUALITY -> HUAWEI_FACE_AUTH_STATUS_QUALITY
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_NOT_FOUND, FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_SCALE_TOO_SMALL -> HUAWEI_FACE_AUTH_STATUS_INSUFFICIENT
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_SCALE_TOO_LARGE -> HUAWEI_FACE_AUTH_STATUS_FAR_FACE
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_OFFSET_LEFT -> HUAWEI_FACE_AUTH_STATUS_FACE_OFFET_LEFT
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_OFFSET_TOP -> HUAWEI_FACE_AUTH_STATUS_FACE_OFFET_TOP
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_OFFSET_RIGHT -> HUAWEI_FACE_AUTH_STATUS_FACE_OFFET_RIGHT
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_OFFSET_BOTTOM -> HUAWEI_FACE_AUTH_STATUS_FACE_OFFET_BOTTOM
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_NOT_COMPLETE -> HUAWEI_FACE_AUTH_STATUS_PARTIAL
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_DARKLIGHT -> HUAWEI_FACE_AUTH_STATUS_DARK
                FaceRecognizeManager.AcquireInfo.FACE_UNLOCK_FACE_HIGHTLIGHT -> HUAWEI_FACE_AUTH_STATUS_BRIGHT
                else -> HUAWEI_FACE_AUTHENTICATOR_FAIL
            }
        }

        fun converHwErrorCodeToHuawei(hwErrorCode: Int): Int {
            val str = TAG
            val stringBuilder = StringBuilder()
            stringBuilder.append(" converHwErrorCodeToHuawei hwErrorCode is ")
            stringBuilder.append(hwErrorCode)
            e(str, stringBuilder.toString())
            return when (hwErrorCode) {
                FaceRecognizeManager.FaceErrorCode.SUCCESS -> HUAWEI_FACE_AUTHENTICATOR_SUCCESS
                FaceRecognizeManager.FaceErrorCode.CANCELED -> BiometricCodes.BIOMETRIC_ERROR_CANCELED
                FaceRecognizeManager.FaceErrorCode.TIMEOUT -> BiometricCodes.BIOMETRIC_ERROR_TIMEOUT
                FaceRecognizeManager.FaceErrorCode.IN_LOCKOUT_MODE -> BiometricCodes.BIOMETRIC_ERROR_LOCKOUT
                FaceRecognizeManager.FaceErrorCode.HAL_INVALIDE, FaceRecognizeManager.FaceErrorCode.INVALID_PARAMETERS, FaceRecognizeManager.FaceErrorCode.ALGORITHM_NOT_INIT, FaceRecognizeManager.FaceErrorCode.FAILED -> BiometricCodes.BIOMETRIC_ERROR_VENDOR
                FaceRecognizeManager.FaceErrorCode.COMPARE_FAIL, FaceRecognizeManager.FaceErrorCode.NO_FACE_DATA, FaceRecognizeManager.FaceErrorCode.OVER_MAX_FACES -> HUAWEI_FACE_AUTHENTICATOR_FAIL
                else -> HUAWEI_FACE_AUTHENTICATOR_FAIL
            }
        }

        private val lock = ReentrantLock()
        fun createInstance(context: Context?) {
            try {
                lock.lock()
                if (instance == null) {
                    instance = HuaweiFaceRecognizeManager(context)
                }
            } finally {
                lock.unlock()
            }
        }
    }

    private var mAuthenticatorCallback: HuaweiFaceManager.AuthenticatorCallback? = null

    //    EMUI 10/0/0
    //    [HuaweiFaceRecognize,  onCallbackEvent gotten reqId 14 type 2 code 1 errCode 9]
    //    [HuaweiFaceRecognize,  onCallbackEvent gotten reqId 14 type 2 code 2 errCode 0]
    //    EMUI 9/1/0
    //    [HuaweiFaceRecognize,  onCallbackEvent gotten reqId 180 type 2 code 1 errCode 9]
    //     [HuaweiFaceRecognize,  onCallbackEvent gotten reqId 180 type 2 code 2 errCode 0]
    //    EMUI 11/0/0
    //    [HuaweiFaceRecognize,  onCallbackEvent gotten reqId 174 type 2 code 1 errCode 1]
    //    MatePad 8T
    //    [HuaweiFaceRecognize, onCallbackEvent gotten reqId 1 type 2 code 1 errCode 1]
    private val mFRCallback: FaceRecognizeCallback = object : FaceRecognizeCallback {
        override fun onCallbackEvent(reqId: Int, type: Int, code: Int, errorCode: Int) {
            var str = TAG
            var stringBuilder = StringBuilder()
            stringBuilder.append(" onCallbackEvent gotten reqId ")
            stringBuilder.append(reqId)
            stringBuilder.append(" type ")
            stringBuilder.append(type)
            stringBuilder.append(" code ")
            stringBuilder.append(code)
            stringBuilder.append(" errCode ")
            stringBuilder.append(errorCode)
            d(str, stringBuilder.toString())
            if (mAuthenticatorCallback == null) {
                e(TAG, "mAuthenticatorCallback empty in onCallbackEvent ")
                return
            }
            if (type != TYPE_CALLBACK_AUTH) {
                str = TAG
                stringBuilder = StringBuilder()
                stringBuilder.append(" gotten not huawei's auth callback reqid ")
                stringBuilder.append(reqId)
                stringBuilder.append(" type ")
                stringBuilder.append(type)
                stringBuilder.append(" code ")
                stringBuilder.append(code)
                stringBuilder.append(" errCode ")
                stringBuilder.append(errorCode)
                e(str, stringBuilder.toString())
            } else
                if (code == CODE_CALLBACK_ACQUIRE) {
                    val result = converHwAcquireInfoToHuawei(errorCode)
                    val str2 = TAG
                    val stringBuilder2 = StringBuilder()
                    stringBuilder2.append(" result ")
                    stringBuilder2.append(result)
                    d(str2, stringBuilder2.toString())
                    if (result != HUAWEI_FACE_AUTHENTICATOR_FAIL) {
                        mAuthenticatorCallback?.onAuthenticationStatus(result)
                    }
                } else if (code == CODE_CALLBACK_RESULT) {
                    val result = converHwErrorCodeToHuawei(errorCode)
                    var str2 = TAG
                    var stringBuilder2 = StringBuilder()
                    stringBuilder2.append(" result ")
                    stringBuilder2.append(result)
                    d(str2, stringBuilder2.toString())
                    if (result == HUAWEI_FACE_AUTHENTICATOR_SUCCESS) {
                        d(TAG, "huawei face auth success")
                        mAuthenticatorCallback?.onAuthenticationSucceeded()
                        mAuthenticatorCallback = null
                    } else if (result != HUAWEI_FACE_AUTHENTICATOR_FAIL) {
                        mAuthenticatorCallback?.onAuthenticationError(result)
                        mAuthenticatorCallback = null
                    } else {
                        mAuthenticatorCallback?.onAuthenticationFailed()
                        str2 = TAG
                        stringBuilder2 = StringBuilder()
                        stringBuilder2.append(" fail reason ")
                        stringBuilder2.append(result)
                        e(str2, stringBuilder2.toString())
                    }
                }
        }
    }

    init {
        if (fRManager == null) {
            fRManager = FaceRecognizeManager(context, mFRCallback)
        }
    }

    fun init(): Int {
        if (fRManager != null) {
            return fRManager?.init() ?: -1
        }
        return -1
    }

    fun release() {
        if (fRManager != null) {
            fRManager?.release()
        }
        fRManager = null
        instance = null
    }

    fun setAuthCallback(authCallback: HuaweiFaceManager.AuthenticatorCallback?) {
        mAuthenticatorCallback = authCallback
    }
}