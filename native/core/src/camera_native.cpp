#include <cstdint>

extern "C" __attribute__((visibility("default"))) std::int32_t camera_native_abi_version() noexcept {
    return 1;
}
