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

package dev.skomlach.biometric.compat.utils.device

import android.os.Build
import androidx.annotation.WorkerThread
import com.jaredrummler.android.device.DeviceName
import dev.skomlach.biometric.compat.utils.SystemPropertiesProxy
import dev.skomlach.biometric.compat.utils.logging.BiometricLoggerImpl
import dev.skomlach.common.contextprovider.AndroidContext
import dev.skomlach.common.network.NetworkApi
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object DeviceModel {

    private var brand = (Build.BRAND ?: "").replace("  ", " ")
    private val model = (Build.MODEL ?: "").replace("  ", " ")
    private val device = (Build.DEVICE ?: "").replace("  ", " ")
    private val appContext = AndroidContext.appContext

    init {
        if (brand == "Amazon") {
            SystemPropertiesProxy.get(appContext, "ro.build.characteristics").let {
                if (it == "tablet")
                    brand = "$brand Kindle"
            }
        }

    }

    fun getNames(): List<Pair<String, String>> {
        val strings = HashMap<String, String>()

        getSimpleDeviceName()?.let {
            val str = fixVendorName(it)
            strings.put(str, str.filter { c ->
                c.isLetterOrDigit() || c.isWhitespace()
            })
        }
        getNameFromAssets()?.let {
            for (s in it) {
                val str = fixVendorName(s)
                strings.put(str, str.filter { c ->
                    c.isLetterOrDigit() || c.isWhitespace()
                })
            }
        }
        getNameFromDatabase()?.let {
            for (s in it) {
                val str = fixVendorName(s)
                strings.put(str, str.filter { c ->
                    c.isLetterOrDigit() || c.isWhitespace()
                })
            }
        }

        val set = HashSet<String>(strings.keys)
        val toRemove = HashSet<String>()
        for (name1 in set) {
            for (name2 in set) {
                if (toRemove.contains(name2))
                    continue
                if (name1.length < name2.length && name2.startsWith(name1, ignoreCase = true))
                    toRemove.add(name1)
            }
        }
        set.removeAll(toRemove)
        val l = set.toMutableList().also {
            it.sortWith { p0, p1 -> p1.length.compareTo(p0.length) }
        }
        val list = ArrayList<Pair<String, String>>()
        for (s in l) {
            list.add(Pair(s, strings[s] ?: continue))
        }
        BiometricLoggerImpl.d("AndroidModel.names $list")
        return list
    }

    private fun fixVendorName(string: String): String {
        val parts = string.split(" ")

        var vendor = parts[0]
        if (vendor[0].isLowerCase()) {
            vendor = Character.toUpperCase(vendor[0]).toString() + vendor.substring(1)
        }
        return (vendor + string.substring(vendor.length, string.length)).trim()
    }

    private fun getSimpleDeviceName(): String? {
        SystemPropertiesProxy.get(appContext, "ro.config.marketing_name").let {
            if (it.isNotEmpty())
                return getName(brand, it)
        }
        SystemPropertiesProxy.get(appContext, "ro.camera.model").let {
            if (it.isNotEmpty())
                return getName(brand, it)
        }
        return if (brand.equals( "Huawei", ignoreCase = true) || brand.equals("Honor", ignoreCase = true)) "$brand $model" else null
    }

    @WorkerThread
    private fun getNameFromAssets(): List<String>? {

        BiometricLoggerImpl.d("AndroidModel.getNameFromAssets started")

        try {
            val json = JSONObject(getJSON() ?: return null)
            for (key in json.keys()) {
                if (brand.equals(key, ignoreCase = true)) {
                    val details = json.getJSONArray(key)
                    for (i in 0 until details.length()) {
                        val jsonObject = details.getJSONObject(i)
                        val m = jsonObject.getString("model")
                        val name = jsonObject.getString("name")
                        val d = jsonObject.getString("device")
                        if (name.isNullOrEmpty()) {
                            continue
                        } else if (!m.isNullOrEmpty() && model.equals(m, ignoreCase = true)) {
                            BiometricLoggerImpl.d("AndroidModel.getNameFromAssets1 - $jsonObject")

                            return mutableListOf<String>().apply {
                                this.add(getName(brand, getFullName(name)))
                                this.add(getName(brand, getFullName(model)))
                            }
                        } else if (!d.isNullOrEmpty() && device.equals(d, ignoreCase = true)) {
                            BiometricLoggerImpl.d("AndroidModel.getNameFromAssets2 - $jsonObject")
                            return mutableListOf<String>().apply {
                                this.add(getName(brand, getFullName(name)))
                                this.add(getName(brand, getFullName(model)))
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            BiometricLoggerImpl.e(e, "AndroidModel")
        }
        return null
    }

    //tools
    private fun getJSON(): String? {
        try {
            //https://github.com/androidtrackers/certified-android-devices/
            val inputStream =
                appContext.assets.open("by_brand.json")
            val byteArrayOutputStream = ByteArrayOutputStream()
            NetworkApi.fastCopy(inputStream, byteArrayOutputStream)
            inputStream.close()
            byteArrayOutputStream.close()
            val data = byteArrayOutputStream.toByteArray()
            return String(data)
        } catch (e: Throwable) {
            BiometricLoggerImpl.e(e)
        }
        return null;
    }

    @WorkerThread
    private fun getNameFromDatabase(): List<String>? {
        val info = DeviceName
            .getDeviceInfo(appContext)
        BiometricLoggerImpl.d("AndroidModel.getNameFromDatabase -{ ${info.manufacturer}; ${info.codename}; ${info.name}; ${info.marketName}; ${info.model}; }")
        return if (info != null) {
            val list = mutableListOf<String>()
            if (info.manufacturer.isNullOrEmpty()) {
                list.add(info.model)
                return list
            } else {
                list.add(
                    getName(
                        if (info.manufacturer?.isNotEmpty() == true) info.manufacturer else brand,
                        getFullName(info.name)
                    )
                )
                list.add(
                    getName(
                        if (info.manufacturer?.isNotEmpty() == true) info.manufacturer else brand,
                        getFullName(info.model)
                    )
                )
            }
            BiometricLoggerImpl.d("AndroidModel.getNameFromDatabase2 -{ $list }")
            list
        } else {
            null
        }
    }

    private fun getFullName(name: String): String {
        val modelParts = model.split(" ")
        val nameParts = name.split(" ")

        return if (modelParts[0].length > nameParts[0].length && modelParts[0].startsWith(
                nameParts[0],
                true
            )
        ) model else name
    }

    private fun getName(vendor: String, model: String): String {
        if (model.startsWith(vendor, true))
            return model
        return "$vendor $model"
    }

}