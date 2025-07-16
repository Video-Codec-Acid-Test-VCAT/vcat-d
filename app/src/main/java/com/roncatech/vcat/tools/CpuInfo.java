package com.roncatech.vcat.tools;

import android.util.Log;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CpuInfo {
    private static final String TAG = "CpuInfo";

    public static class CpuCore {
        public final int cpu_part;
        public final int maxMHz;

        public String getCoreDesc(){
            Map<Integer, String> partMap = CpuInfo.getCpuPartMap();

            return String.format("%s: %d MHz", partMap.get(this.cpu_part), this.maxMHz);
        }

        public CpuCore(int cpu_part, int maxMHx) {
            this.cpu_part = cpu_part;
            this.maxMHz = maxMHx;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CpuCore)) return false;
            CpuCore that = (CpuCore) o;
            return cpu_part == that.cpu_part
                    && maxMHz   == that.maxMHz;
        }

        @Override
        public int hashCode() {
            return Objects.hash(cpu_part, maxMHz);
        }

    }

    public final String armArchitecture;
    public final List<CpuCore> cores;

    public CpuInfo(){
        this.armArchitecture = CpuInfo.getArmArchitecture();
        this.cores = getCpuCores();
    }

    public CpuInfo(String armArchitecture, List<CpuCore> cores){
        this.armArchitecture = armArchitecture;
        this.cores = cores;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CpuInfo)) return false;
        CpuInfo that = (CpuInfo) o;
        return Objects.equals(this.armArchitecture, that.armArchitecture)
                && Objects.equals(this.cores, that.cores);
    }

    @Override
    public int hashCode() {
        return Objects.hash(armArchitecture, cores);
    }

    /**
     * Static inner adapter to handle custom JSON serialization/deserialization
     */
    public static class Adapter
            implements JsonSerializer<CpuInfo>, JsonDeserializer<CpuInfo> {

        private static final Pattern CORE_PATTERN =
                Pattern.compile("^(.+) \\(0x([0-9a-fA-F]+)\\): (\\d+) MHz$");

        @Override
        public JsonElement serialize(CpuInfo src, Type typeOfSrc, JsonSerializationContext ctx) {
            JsonObject cpuJ = new JsonObject();
            cpuJ.addProperty("architecture", src.armArchitecture);
            JsonObject coresJ = new JsonObject();
            for (int i = 0; i < src.cores.size(); i++) {
                coresJ.addProperty("core" + i, src.cores.get(i).getCoreDesc());
            }
            cpuJ.add("cores", coresJ);
            return cpuJ;
        }

        @Override
        public CpuInfo deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext ctx) throws JsonParseException {
            JsonObject o = json.getAsJsonObject();
            String arch = o.get("architecture").getAsString();
            JsonObject coresJ = o.getAsJsonObject("cores");
            List<CpuCore> list = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : coresJ.entrySet()) {
                String desc = e.getValue().getAsString();
                Matcher m = CORE_PATTERN.matcher(desc);
                if (m.matches()) {
                    int part = Integer.parseInt(m.group(2), 16);
                    int mhx  = Integer.parseInt(m.group(3));
                    list.add(new CpuCore(part, mhx));
                }
            }
            return new CpuInfo(arch, list);
        }
    }


    public static Map<Integer, String> getCpuPartMap() {

        if(cpuPartMap == null){
            cpuPartMap = new HashMap<>();
            cpuPartMap.put(0xd03, "Cortex-A53 (0xd03)");
            cpuPartMap.put(0xd05, "Cortex-A55 (0xd05)");
            cpuPartMap.put(0xd07, "Cortex-A57 (0xd07)");
            cpuPartMap.put(0xd08, "Cortex-A72 (0xd08)");
            cpuPartMap.put(0xd09, "Cortex-A73 (0xd09)");
            cpuPartMap.put(0xd0a, "Cortex-A75 (0xd0a)");
            cpuPartMap.put(0xd41, "Cortex-A78 (0xd41)");
            cpuPartMap.put(0xd44, "Cortex-X1 (0xd44)");
            cpuPartMap.put(0xd4a, "Cortex-A76 (0xd4a)");
            cpuPartMap.put(0xd4b, "Cortex-X2 (0xd4b)");
            cpuPartMap.put(0xd4c, "Cortex-A510 (0xd4c)");
            cpuPartMap.put(0xd4d, "Cortex-A710 (0xd4d)");
            cpuPartMap.put(0xd4e, "Cortex-X3 (0xd4e)");
        }
        return cpuPartMap;
    }

    private static Map<Integer, String> cpuPartMap = null;

    public static String getArmArchitecture() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("CPU architecture")) {
                    return "ARMv" + line.split(":")[1].trim();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read ARM version", e);
        }
        return "Unknown";
    }

    public static List<String> getCoreTypes() {
        List<String> coreTypes = new ArrayList<>();

        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            int currentCore = -1;

            while ((line = reader.readLine()) != null) {
                if (line.startsWith("processor")) {
                    // Reset for a new core
                    String[] parts = line.split(":");
                    currentCore = Integer.parseInt(parts[1].trim());
                    // Ensure capacity
                    while (coreTypes.size() <= currentCore) coreTypes.add(null);
                }

                Map<Integer, String> partMap = getCpuPartMap();

                if (line.contains("CPU part") && currentCore >= 0) {
                    String[] parts = line.split(":");
                    String partIdStr = parts[1].trim().replace("0x", "");
                    if (partIdStr.startsWith("0x") || partIdStr.startsWith("0X")) {
                        partIdStr = partIdStr.substring(2); // remove "0x"
                    }
                    int partId = Integer.parseInt(partIdStr, 16);
                    String type = partMap.getOrDefault(partId, "Unknown (0x" + partIdStr + ")");
                    coreTypes.set(currentCore, type);
                }
            }

            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return coreTypes;
    }


    public static Map<String, Integer> getMaxFrequenciesMHz() {
        Map<String, Integer> freqMap = new HashMap<>();
        int i = 0;
        while (true) {
            String path = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq";
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String line = reader.readLine();
                if (line != null) {
                    int mhz = Integer.parseInt(line.trim()) / 1000;
                    freqMap.put("core" + i, mhz);
                }
            } catch (IOException e) {
                break; // Assume we've reached the last CPU
            }
            i++;
        }
        return freqMap;
    }

    public static Map<String, Object> getCpuInfoJson() {
        Map<String, Object> cpuJson = new LinkedHashMap<>();

        String arch = "ARMv8"; // assume for now
        LinkedHashMap<String, String> coreMap = new LinkedHashMap<>();

        List<String> coreTypes = getCoreTypes();          // core0 = A55, etc
        Map<String, Integer> freqs = getMaxFrequenciesMHz();    // core0 = 1612, etc

        int numCores = Math.min(coreTypes.size(), freqs.size());

        for (int i = 0; i < numCores; i++) {
            String label = "core" + i;
            String type = coreTypes.get(i);
            Integer _freq = freqs.get(label);
            int freq = (_freq == null) ? -1 : _freq;
            coreMap.put(label, type + ": " + freq + " MHz");
        }

        cpuJson.put("architecture", arch);
        cpuJson.put("cores", coreMap);
        return cpuJson;
    }

    /**
     * @return one CpuCore entry per physical CPU core
     */
    private static List<CpuCore> getCpuCores() {
        int coreCount = Runtime.getRuntime().availableProcessors();
        List<CpuCore> cores = new ArrayList<>(coreCount);

        // Read entire /proc/cpuinfo
        StringBuilder cpuInfo = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                cpuInfo.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }

        // Split into per-core sections
        String[] sections = cpuInfo.toString().split("\\n\\n");

        for (int i = 0; i < coreCount; i++) {
            String section = i < sections.length ? sections[i] : "";
            int part = -1;

            // parse "CPU part    : 0xd05"
            for (String ln : section.split("\\n")) {
                if (ln.startsWith("CPU part")) {
                    String[] kv = ln.split(":", 2);
                    if (kv.length == 2) {
                        try {
                            part = Integer.decode(kv[1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    break;
                }
            }

            // read max freq (kHz) → MHz
            int mhz = 0;
            String path = String.format(Locale.US,
                    "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
            try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                String khz = br.readLine();
                if (khz != null) {
                    mhz = Integer.parseInt(khz.trim()) / 1000;
                }
            } catch (IOException | NumberFormatException ignored) {
            }

            cores.add(new CpuCore(part, mhz));
        }

        return cores;
    }

    /**
     * Reads a CPU frequency value (in kHz) from the given sysfs file.
     *
     * @param path the absolute sysfs file path
     * @return the frequency in kHz, or -1 if the file can't be read or parsed
     */
    private static long readCpuFreq(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            if (line != null) {
                try {
                    return Long.parseLong(line.trim());
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid number in " + path + ": " + line, e);
                }
            } else {
                Log.e(TAG, "Empty file: " + path);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading CPU frequency from " + path, e);
        }
        return -1L;
    }

    /**
     * @return the minimum CPU frequency (in kHz) for cpu0, or -1 on error.
     */
    public static long getMinCpuFrequency() {
        return readCpuFreq("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq");
    }

    /**
     * @return the maximum CPU frequency (in kHz) for cpu0, or -1 on error.
     */
    public static long getMaxCpuFrequency() {
        return readCpuFreq("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");
    }

}


