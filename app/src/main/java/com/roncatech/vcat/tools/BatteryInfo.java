/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of VCAT.
 *
 * VCAT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VCAT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VCAT. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting VCAT itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */

package com.roncatech.vcat.tools;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class BatteryInfo {
    private static String TAG = "BatteryInfo";

    /**
     * Uses reflection on the hidden com.android.internal.os.PowerProfile class
     * to retrieve the battery’s design capacity in mAh.
     * Returns –1 on any failure.
     */
    public static double getBatteryDesignCapacity(Context context) {
        final String POWER_PROFILE_CLASS = "com.android.internal.os.PowerProfile";
        try {
            // 1) Locate the PowerProfile class
            Class<?> ppClass = Class.forName(POWER_PROFILE_CLASS);

            // 2) Grab its constructor that takes a Context
            Constructor<?> ctor = ppClass.getDeclaredConstructor(Context.class);
            ctor.setAccessible(true);

            // 3) Instantiate PowerProfile
            Object powerProfile = ctor.newInstance(context);

            // 4) Find the getBatteryCapacity() method
            Method getCapacity = ppClass.getDeclaredMethod("getBatteryCapacity");
            getCapacity.setAccessible(true);

            // 5) Invoke it
            Object result = getCapacity.invoke(powerProfile);

            // 6) Convert to double, handling possible primitive wrappers
            if (result instanceof Double) {
                return (Double) result;
            } else if (result instanceof Float) {
                return ((Float) result).doubleValue();
            } else if (result instanceof Integer) {
                return ((Integer) result).doubleValue();
            } else {
                Log.w(TAG, "Unexpected return type for getBatteryCapacity(): "
                        + result.getClass().getName());
            }
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "PowerProfile class not found", e);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "getBatteryCapacity() method not found", e);
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
            Log.w(TAG, "Error invoking PowerProfile.getBatteryCapacity()", e);
        }
        return -1;
    }

    public static int getBatteryLevel(Context context) {
        // 1) Try BatteryManager.BATTERY_PROPERTY_CAPACITY (0–100%)
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (capacity >= 0 && capacity <= 100) {
                return capacity;
            }
        }

        // 2) Fallback to ACTION_BATTERY_CHANGED sticky intent
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent status = context.registerReceiver(null, ifilter);
        if (status == null) {
            Log.w(TAG, "Unable to retrieve battery status via Intent");
            return -1;
        }
        int level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level < 0 || scale <= 0) {
            Log.e(TAG, "Invalid battery readings: level=" + level + " scale=" + scale);
            return -1;
        }

        // Compute percentage in integer math
        return (level * 100) / scale;
    }

}

