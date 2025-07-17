package com.roncatech.vcat.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class CpuInfoTest {
    private static final String JSON =
            "{\n" +
                    "  \"architecture\": \"ARMv8\",\n" +
                    "  \"cores\": {\n" +
                    "    \"core0\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core1\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core2\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core3\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core4\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core5\": \"Cortex-A55 (0xd05): 1612 MHz\",\n" +
                    "    \"core6\": \"Cortex-A75 (0xd0a): 1612 MHz\",\n" +
                    "    \"core7\": \"Cortex-A75 (0xd0a): 1612 MHz\"\n" +
                    "  }\n" +
                    "}";

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(CpuInfo.class, new CpuInfo.Adapter())
            .setPrettyPrinting()
            .create();

    public static CpuInfo buildTestInstance(){
        List<CpuInfo.CpuCore> cores = new ArrayList<>();
        for(int i = 0; i < 6; ++i){
            cores.add(new CpuInfo.CpuCore(0xd05, 1612));
        }
        cores.add(new CpuInfo.CpuCore(0xd0a, 1612));
        cores.add(new CpuInfo.CpuCore(0xd0a, 1612));

        return new CpuInfo("ARMv8", cores);

    }

    @Test
    public void testSerialize() {
        CpuInfo testInst = buildTestInstance();

        String jsonOutput = gson.toJson(testInst, CpuInfo.class);
        String exptOutput = JSON;
        assertEquals(exptOutput, jsonOutput);
    }

    @Test
    public void testDeserialize() {
        CpuInfo expected = buildTestInstance();

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(CpuInfo.class, new CpuInfo.Adapter())
                .setPrettyPrinting()
                .create();

        CpuInfo testInstance = gson.fromJson(JSON, CpuInfo.class);

        assertEquals(expected, testInstance);
    }
}
