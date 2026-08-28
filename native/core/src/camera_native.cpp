#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraMetadataTags.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <iomanip>
#include <locale>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

namespace {
constexpr int kMaxCameraIds = 256;
constexpr std::size_t kMaxArrayValues = 64;

using ManagerCreateFn = ACameraManager* (*)();
using ManagerDeleteFn = void (*)(ACameraManager*);
using GetCameraIdListFn = camera_status_t (*)(ACameraManager*, ACameraIdList**);
using DeleteCameraIdListFn = void (*)(ACameraIdList*);
using GetCharacteristicsFn = camera_status_t (*)(ACameraManager*, const char*, ACameraMetadata**);
using MetadataFreeFn = void (*)(ACameraMetadata*);
using GetConstEntryFn = camera_status_t (*)(const ACameraMetadata*, std::uint32_t, ACameraMetadata_const_entry*);

struct CameraNdkApi {
    void* handle = nullptr;
    ManagerCreateFn manager_create = nullptr;
    ManagerDeleteFn manager_delete = nullptr;
    GetCameraIdListFn get_camera_id_list = nullptr;
    DeleteCameraIdListFn delete_camera_id_list = nullptr;
    GetCharacteristicsFn get_characteristics = nullptr;
    MetadataFreeFn metadata_free = nullptr;
    GetConstEntryFn get_const_entry = nullptr;

    ~CameraNdkApi() {
        if (handle != nullptr) {
            dlclose(handle);
        }
    }

    bool load() {
        handle = dlopen("libcamera2ndk.so", RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) return false;
        manager_create = reinterpret_cast<ManagerCreateFn>(dlsym(handle, "ACameraManager_create"));
        manager_delete = reinterpret_cast<ManagerDeleteFn>(dlsym(handle, "ACameraManager_delete"));
        get_camera_id_list = reinterpret_cast<GetCameraIdListFn>(dlsym(handle, "ACameraManager_getCameraIdList"));
        delete_camera_id_list = reinterpret_cast<DeleteCameraIdListFn>(dlsym(handle, "ACameraManager_deleteCameraIdList"));
        get_characteristics = reinterpret_cast<GetCharacteristicsFn>(dlsym(handle, "ACameraManager_getCameraCharacteristics"));
        metadata_free = reinterpret_cast<MetadataFreeFn>(dlsym(handle, "ACameraMetadata_free"));
        get_const_entry = reinterpret_cast<GetConstEntryFn>(dlsym(handle, "ACameraMetadata_getConstEntry"));
        return manager_create != nullptr && manager_delete != nullptr && get_camera_id_list != nullptr &&
               delete_camera_id_list != nullptr && get_characteristics != nullptr && metadata_free != nullptr &&
               get_const_entry != nullptr;
    }
};

std::string json_escape(const char* value) {
    if (value == nullptr) return {};
    std::ostringstream out;
    for (const unsigned char ch : std::string(value)) {
        switch (ch) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (ch < 0x20) {
                    out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << static_cast<int>(ch)
                        << std::dec << std::setfill(' ');
                } else {
                    out << static_cast<char>(ch);
                }
        }
    }
    return out.str();
}

std::optional<int> read_u8(const CameraNdkApi& api, const ACameraMetadata* metadata, std::uint32_t tag) {
    ACameraMetadata_const_entry entry{};
    if (api.get_const_entry(metadata, tag, &entry) != ACAMERA_OK || entry.count == 0 || entry.data.u8 == nullptr) {
        return std::nullopt;
    }
    return static_cast<int>(entry.data.u8[0]);
}

std::optional<int> read_i32(const CameraNdkApi& api, const ACameraMetadata* metadata, std::uint32_t tag) {
    ACameraMetadata_const_entry entry{};
    if (api.get_const_entry(metadata, tag, &entry) != ACAMERA_OK || entry.count == 0 || entry.data.i32 == nullptr) {
        return std::nullopt;
    }
    return static_cast<int>(entry.data.i32[0]);
}

std::vector<int> read_u8_array(const CameraNdkApi& api, const ACameraMetadata* metadata, std::uint32_t tag) {
    ACameraMetadata_const_entry entry{};
    if (api.get_const_entry(metadata, tag, &entry) != ACAMERA_OK || entry.count == 0 || entry.data.u8 == nullptr) {
        return {};
    }
    const std::size_t count = std::min<std::size_t>(entry.count, kMaxArrayValues);
    std::vector<int> values;
    values.reserve(count);
    for (std::size_t i = 0; i < count; ++i) values.push_back(static_cast<int>(entry.data.u8[i]));
    return values;
}

std::vector<float> read_float_array(const CameraNdkApi& api, const ACameraMetadata* metadata, std::uint32_t tag) {
    ACameraMetadata_const_entry entry{};
    if (api.get_const_entry(metadata, tag, &entry) != ACAMERA_OK || entry.count == 0 || entry.data.f == nullptr) {
        return {};
    }
    const std::size_t count = std::min<std::size_t>(entry.count, kMaxArrayValues);
    std::vector<float> values;
    values.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        if (std::isfinite(entry.data.f[i])) values.push_back(entry.data.f[i]);
    }
    return values;
}

std::optional<std::pair<float, float>> read_float_pair(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    std::uint32_t tag
) {
    ACameraMetadata_const_entry entry{};
    if (api.get_const_entry(metadata, tag, &entry) != ACAMERA_OK || entry.count < 2 || entry.data.f == nullptr) {
        return std::nullopt;
    }
    if (!std::isfinite(entry.data.f[0]) || !std::isfinite(entry.data.f[1])) return std::nullopt;
    return std::pair<float, float>{entry.data.f[0], entry.data.f[1]};
}

std::optional<std::pair<int, int>> read_i32_pair(
    const CameraNdkApi& api,
    const ACameraMetadata* metadata,
    std::uint32_t tag
) {
    ACameraMetadata_const_entry entry{};
    if (api.get_const_entry(metadata, tag, &entry) != ACAMERA_OK || entry.count < 2 || entry.data.i32 == nullptr) {
        return std::nullopt;
    }
    return std::pair<int, int>{entry.data.i32[0], entry.data.i32[1]};
}

void append_int_array(std::ostringstream& out, const std::vector<int>& values) {
    out << '[';
    for (std::size_t i = 0; i < values.size(); ++i) {
        if (i != 0) out << ',';
        out << values[i];
    }
    out << ']';
}

void append_float_array(std::ostringstream& out, const std::vector<float>& values) {
    out << '[';
    for (std::size_t i = 0; i < values.size(); ++i) {
        if (i != 0) out << ',';
        out << std::setprecision(9) << values[i];
    }
    out << ']';
}

void append_optional_int(std::ostringstream& out, const std::optional<int>& value) {
    if (value.has_value()) out << *value; else out << "null";
}

std::string collect_ndk_evidence() {
    CameraNdkApi api;
    if (!api.load()) {
        return R"({"schema":1,"available":false,"truncated":false,"error":"camera2ndk-unavailable","cameras":[]})";
    }

    ACameraManager* manager = api.manager_create();
    if (manager == nullptr) {
        return R"({"schema":1,"available":false,"truncated":false,"error":"manager-create-failed","cameras":[]})";
    }

    ACameraIdList* id_list = nullptr;
    const camera_status_t list_status = api.get_camera_id_list(manager, &id_list);
    if (list_status != ACAMERA_OK || id_list == nullptr) {
        api.manager_delete(manager);
        std::ostringstream failed;
        failed << R"({"schema":1,"available":true,"truncated":false,"error":"enumeration-status-)"
               << static_cast<int>(list_status) << R"(","cameras":[]})";
        return failed.str();
    }

    const int count = std::min(id_list->numCameras, kMaxCameraIds);
    const bool truncated = id_list->numCameras > kMaxCameraIds;
    std::ostringstream out;
    out.imbue(std::locale::classic());
    out << R"({"schema":1,"available":true,"truncated":)" << (truncated ? "true" : "false")
        << R"(,"error":null,"cameras":[)";

    for (int index = 0; index < count; ++index) {
        if (index != 0) out << ',';
        const char* camera_id = id_list->cameraIds[index];
        out << R"({"id":")" << json_escape(camera_id) << R"(")";

        ACameraMetadata* metadata = nullptr;
        const camera_status_t metadata_status =
            camera_id == nullptr ? ACAMERA_ERROR_INVALID_PARAMETER : api.get_characteristics(manager, camera_id, &metadata);
        out << R"(,"status":)" << static_cast<int>(metadata_status);

        if (metadata_status == ACAMERA_OK && metadata != nullptr) {
            out << R"(,"lensFacing":)";
            append_optional_int(out, read_u8(api, metadata, ACAMERA_LENS_FACING));
            out << R"(,"hardwareLevel":)";
            append_optional_int(out, read_u8(api, metadata, ACAMERA_INFO_SUPPORTED_HARDWARE_LEVEL));
            out << R"(,"sensorOrientation":)";
            append_optional_int(out, read_i32(api, metadata, ACAMERA_SENSOR_ORIENTATION));
            out << R"(,"focalLengthsMm":)";
            append_float_array(out, read_float_array(api, metadata, ACAMERA_LENS_INFO_AVAILABLE_FOCAL_LENGTHS));
            out << R"(,"availableCapabilities":)";
            append_int_array(out, read_u8_array(api, metadata, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES));

            const auto physical_size = read_float_pair(api, metadata, ACAMERA_SENSOR_INFO_PHYSICAL_SIZE);
            out << R"(,"sensorPhysicalSizeMm":)";
            if (physical_size.has_value()) {
                out << '[' << std::setprecision(9) << physical_size->first << ',' << physical_size->second << ']';
            } else {
                out << "null";
            }

            const auto pixel_array = read_i32_pair(api, metadata, ACAMERA_SENSOR_INFO_PIXEL_ARRAY_SIZE);
            out << R"(,"pixelArraySize":)";
            if (pixel_array.has_value()) {
                out << '[' << pixel_array->first << ',' << pixel_array->second << ']';
            } else {
                out << "null";
            }
            api.metadata_free(metadata);
        } else {
            out << R"(,"lensFacing":null,"hardwareLevel":null,"sensorOrientation":null,"focalLengthsMm":[],"availableCapabilities":[],"sensorPhysicalSizeMm":null,"pixelArraySize":null)";
        }
        out << '}';
    }

    out << "]}";
    api.delete_camera_id_list(id_list);
    api.manager_delete(manager);
    return out.str();
}
}  // namespace

extern "C" __attribute__((visibility("default"))) std::int32_t camera_native_abi_version() noexcept {
    return 1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sahidcode404_camera_core_camera_discovery_NdkCameraEvidence_nativeCollectEncoded(
    JNIEnv* env,
    jobject /* thiz */
) noexcept {
    try {
        const std::string encoded = collect_ndk_evidence();
        return env->NewStringUTF(encoded.c_str());
    } catch (...) {
        return env->NewStringUTF(
            R"({"schema":1,"available":false,"truncated":false,"error":"native-exception","cameras":[]})"
        );
    }
}
