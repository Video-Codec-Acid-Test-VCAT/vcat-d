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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Test;

import static org.junit.Assert.*;
import java.util.Locale;

public class DeviceInfoTest {
    private static final String JSON =
            "{\n" +
                    "  \"manufacturer\": \"motorola\",\n" +
                    "  \"model\": \"moto e14\",\n" +
                    "  \"soc_manufacturer\": \"Spreadtrum\",\n" +
                    "  \"soc\": \"T606\",\n" +
                    "  \"android_version\": \"14 (32-bit)\",\n" +
                    "  \"cpu\": {\n" +
                    "    \"architecture\": \"ARMv8\",\n" +
                    "    \"cores\": {\n" +
                    "      \"core0\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "      \"core1\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "      \"core2\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "      \"core3\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "      \"core4\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "      \"core5\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "      \"core6\": \"Cortex-A75 (0xd0a): 1612 MHz\",\n" +
                    "      \"core7\": \"Cortex-A75 (0xd0a): 1612 MHz\"\n" +
                    "    }\n" +
                    "  },\n" +
                    "  \"display_resolution\": {\n" +
                    "    \"width\": 720,\n" +
                    "    \"height\": 1449\n" +
                    "  },\n" +
                    "  \"memory\": {\n" +
                    "    \"total\": \"1.78 GB\",\n" +
                    "    \"available\": \"0.59 GB\"\n" +
                    "  },\n" +
                    "  \"storage\": {\n" +
                    "    \"total\": \"52.77 GB\",\n" +
                    "    \"available\": \"43.38 GB\"\n" +
                    "  }\n" +
                    "}";

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(CpuInfo.class, new CpuInfo.Adapter())
            .registerTypeAdapter(DeviceInfo.class, new DeviceInfo.Adapter())
            .setPrettyPrinting()
            .create();

    @Test
    public void testSerialize() {
        // Deserialize first to get a populated DeviceInfo
        DeviceInfo info = gson.fromJson(JSON, DeviceInfo.class);
        // Serialize back
        String jsonOutput = gson.toJson(info, DeviceInfo.class);
        assertEquals(JSON, jsonOutput);
    }

    public static String long2GBStr(long longVal) {
        double gb = longVal / (1024.0 * 1024.0 * 1024.0);
        return String.format(Locale.US, "%.2f GB", gb);
    }

    public static DeviceInfo buildTestInstance(){
        CpuInfo cpu = CpuInfoTest.buildTestInstance();
        DeviceInfo.DisplayResolution dis = new DeviceInfo.DisplayResolution(720, 1449);
        DeviceInfo.MemoryInfo mem = new DeviceInfo.MemoryInfo(1911260446L, 633507676L);
        DeviceInfo.MemoryInfo stg = new DeviceInfo.MemoryInfo(56661356052L, 46578920325L);

        DeviceInfo ret = new DeviceInfo(
                "motorola", "moto e14",
                "Spreadtrum", "T606",
                "14 (32-bit)",
                mem, stg, cpu, dis);

        return ret;
    }


    @Test
    public void testDeserialize() {
        DeviceInfo result = gson.fromJson(JSON, DeviceInfo.class);
        assertNotNull(result);

        DeviceInfo expected = buildTestInstance();
        assertEquals(expected, result);
    }
}
