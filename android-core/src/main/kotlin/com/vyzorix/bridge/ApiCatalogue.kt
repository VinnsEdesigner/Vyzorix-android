package com.vyzorix.bridge

import com.vyzorix.bridge.apis.AccessibilityApi
import com.vyzorix.bridge.apis.AirplaneModeApi
import com.vyzorix.bridge.apis.AppStorageApi
import com.vyzorix.bridge.apis.AudioStateApi
import com.vyzorix.bridge.apis.BatteryApi
import com.vyzorix.bridge.apis.BatteryHistoryApi
import com.vyzorix.bridge.apis.BluetoothControlApi
import com.vyzorix.bridge.apis.BluetoothStateApi
import com.vyzorix.bridge.apis.BrightnessApi
import com.vyzorix.bridge.apis.BuildFingerprintApi
import com.vyzorix.bridge.apis.CallCapabilityApi
import com.vyzorix.bridge.apis.CallControlApi
import com.vyzorix.bridge.apis.CameraListApi
import com.vyzorix.bridge.apis.ClipboardStateApi
import com.vyzorix.bridge.apis.CpuInfoApi
import com.vyzorix.bridge.apis.DefaultAppsApi
import com.vyzorix.bridge.apis.DeveloperOptionsApi
import com.vyzorix.bridge.apis.DeviceAdminActionApi
import com.vyzorix.bridge.apis.DeviceAdminRequestApi
import com.vyzorix.bridge.apis.DeviceCredentialsApi
import com.vyzorix.bridge.apis.DeviceInfoApi
import com.vyzorix.bridge.apis.DeviceOwnerApi
import com.vyzorix.bridge.apis.DeviceOwnerCapabilitiesApi
import com.vyzorix.bridge.apis.DisplayInfoApi
import com.vyzorix.bridge.apis.DnsServersApi
import com.vyzorix.bridge.apis.DoNotDisturbApi
import com.vyzorix.bridge.apis.ExternalStorageApi
import com.vyzorix.bridge.apis.FileDescriptorsApi
import com.vyzorix.bridge.apis.FontScaleApi
import com.vyzorix.bridge.apis.HapticSettingsApi
import com.vyzorix.bridge.apis.InputDevicesApi
import com.vyzorix.bridge.apis.InputMethodApi
import com.vyzorix.bridge.apis.InstalledAppsApi
import com.vyzorix.bridge.apis.KernelInfoApi
import com.vyzorix.bridge.apis.LoadAverageApi
import com.vyzorix.bridge.apis.LocaleInfoApi
import com.vyzorix.bridge.apis.LocationApi
import com.vyzorix.bridge.apis.LogcatApi
import com.vyzorix.bridge.apis.MemoryInfoApi
import com.vyzorix.bridge.apis.MountPointsApi
import com.vyzorix.bridge.apis.NetworkApi
import com.vyzorix.bridge.apis.NetworkInterfacesApi
import com.vyzorix.bridge.apis.NfcStateApi
import com.vyzorix.bridge.apis.NotificationPolicyApi
import com.vyzorix.bridge.apis.PackageInfoApi
import com.vyzorix.bridge.apis.PackagesDumpApi
import com.vyzorix.bridge.apis.PhoneStateApi
import com.vyzorix.bridge.apis.PhoneStateDetailedApi
import com.vyzorix.bridge.apis.PowerSaveApi
import com.vyzorix.bridge.apis.ProcessListApi
import com.vyzorix.bridge.apis.ProxyStateApi
import com.vyzorix.bridge.apis.RotationStateApi
import com.vyzorix.bridge.apis.ScreenStateApi
import com.vyzorix.bridge.apis.ScreenTimeoutApi
import com.vyzorix.bridge.apis.SecurityPatchApi
import com.vyzorix.bridge.apis.SensorListApi
import com.vyzorix.bridge.apis.SmsCapabilityApi
import com.vyzorix.bridge.apis.SmsReadApi
import com.vyzorix.bridge.apis.SmsReceivedApi
import com.vyzorix.bridge.apis.StorageAllFilesApi
import com.vyzorix.bridge.apis.StorageApi
import com.vyzorix.bridge.apis.StorageVolumesApi
import com.vyzorix.bridge.apis.SystemPropertiesApi
import com.vyzorix.bridge.apis.ThermalApi
import com.vyzorix.bridge.apis.TimeInfoApi
import com.vyzorix.bridge.apis.UsbAttachApi
import com.vyzorix.bridge.apis.UsbControlApi
import com.vyzorix.bridge.apis.UsbDetachApi
import com.vyzorix.bridge.apis.UsbStateApi
import com.vyzorix.bridge.apis.VpnStateApi
import com.vyzorix.bridge.apis.WallpaperApi
import com.vyzorix.bridge.apis.WifiControlApi
import com.vyzorix.bridge.apis.WifiStateApi

/**
 * Every read-only bridge API, in one place.
 *
 * ## Why this exists
 *
 * The router's registrations were a chain of `.register(...)` calls in the service,
 * which meant three things had to agree — the registration, `apis.txt`, and the
 * catalog test — and nothing made them. With six APIs that was survivable; with
 * fifty it is not, and the failure is silent: a name in `apis.txt` the router does
 * not serve is a command that exists and fails at call time, and a handler missing
 * from `apis.txt` is one an operator simply cannot reach.
 *
 * So the list is a **value** here rather than a chain of calls. The service registers
 * from it, `ApiCatalogTest` reads it, and `scripts/sync-apis-txt.py` writes `apis.txt`
 * from it — one source, three consumers, and the test is what proves they agree.
 *
 * ## What is deliberately not here
 *
 * `session` is absent: it needs the service's session table, so it is constructed
 * with callbacks and registered separately. It is still listed in [names] because
 * `apis.txt` must carry it — the container's `session` command is real.
 *
 * ## Why every one is read-only
 *
 * These APIs read a fact. Anything that changes device state would need its own
 * risk tier and its own audit, so writes are added deliberately and individually
 * rather than appearing in a bulk list. The tag vocabulary has `op:write` for when
 * they arrive.
 */
object ApiCatalogue {

    /**
     * The read-only handlers, by API name.
     *
     * A `LinkedHashMap` so registration order is stable and a diff of the resulting
     * `apis.txt` is readable.
     */
    val handlers: Map<String, ApiHandler> =
        linkedMapOf(
            AccessibilityApi.NAME to AccessibilityApi,
            AirplaneModeApi.NAME to AirplaneModeApi,
            AppStorageApi.NAME to AppStorageApi,
            AudioStateApi.NAME to AudioStateApi,
            BatteryApi.NAME to BatteryApi,
            BatteryHistoryApi.NAME to BatteryHistoryApi,
            BluetoothStateApi.NAME to BluetoothStateApi,
            BrightnessApi.NAME to BrightnessApi,
            BuildFingerprintApi.NAME to BuildFingerprintApi,
            CallCapabilityApi.NAME to CallCapabilityApi,
            CallControlApi.NAME to CallControlApi,
            CameraListApi.NAME to CameraListApi,
            ClipboardStateApi.NAME to ClipboardStateApi,
            CpuInfoApi.NAME to CpuInfoApi,
            DefaultAppsApi.NAME to DefaultAppsApi,
            DeveloperOptionsApi.NAME to DeveloperOptionsApi,
            DeviceOwnerApi.NAME to DeviceOwnerApi,
            DeviceOwnerCapabilitiesApi.NAME to DeviceOwnerCapabilitiesApi,
            DeviceInfoApi.NAME to DeviceInfoApi,
            DeviceAdminRequestApi.NAME to DeviceAdminRequestApi,
            DeviceAdminActionApi.NAME to DeviceAdminActionApi,
            DeviceCredentialsApi.NAME to DeviceCredentialsApi,
            DisplayInfoApi.NAME to DisplayInfoApi,
            DnsServersApi.NAME to DnsServersApi,
            DoNotDisturbApi.NAME to DoNotDisturbApi,
            ExternalStorageApi.NAME to ExternalStorageApi,
            FileDescriptorsApi.NAME to FileDescriptorsApi,
            FontScaleApi.NAME to FontScaleApi,
            HapticSettingsApi.NAME to HapticSettingsApi,
            InputDevicesApi.NAME to InputDevicesApi,
            InputMethodApi.NAME to InputMethodApi,
            InstalledAppsApi.NAME to InstalledAppsApi,
            KernelInfoApi.NAME to KernelInfoApi,
            LoadAverageApi.NAME to LoadAverageApi,
            LocaleInfoApi.NAME to LocaleInfoApi,
            LocationApi.NAME to LocationApi,
            LogcatApi.NAME to LogcatApi,
            MemoryInfoApi.NAME to MemoryInfoApi,
            MountPointsApi.NAME to MountPointsApi,
            NetworkApi.NAME to NetworkApi,
            NetworkInterfacesApi.NAME to NetworkInterfacesApi,
            NfcStateApi.NAME to NfcStateApi,
            NotificationPolicyApi.NAME to NotificationPolicyApi,
            PackageInfoApi.NAME to PackageInfoApi,
            PackagesDumpApi.NAME to PackagesDumpApi,
            PhoneStateApi.NAME to PhoneStateApi,
            PhoneStateDetailedApi.NAME to PhoneStateDetailedApi,
            PowerSaveApi.NAME to PowerSaveApi,
            ProcessListApi.NAME to ProcessListApi,
            ProxyStateApi.NAME to ProxyStateApi,
            RotationStateApi.NAME to RotationStateApi,
            ScreenStateApi.NAME to ScreenStateApi,
            SmsCapabilityApi.NAME to SmsCapabilityApi,
            SmsReadApi.NAME to SmsReadApi,
            ScreenTimeoutApi.NAME to ScreenTimeoutApi,
            SecurityPatchApi.NAME to SecurityPatchApi,
            SensorListApi.NAME to SensorListApi,
            StorageAllFilesApi.NAME to StorageAllFilesApi,
            StorageApi.NAME to StorageApi,
            StorageVolumesApi.NAME to StorageVolumesApi,
            SystemPropertiesApi.NAME to SystemPropertiesApi,
            ThermalApi.NAME to ThermalApi,
            TimeInfoApi.NAME to TimeInfoApi,
            UsbControlApi.NAME to UsbControlApi,
            WallpaperApi.NAME to WallpaperApi,
            WifiControlApi.NAME to WifiControlApi,
            VpnStateApi.NAME to VpnStateApi,
            WifiStateApi.NAME to WifiStateApi,
        )

    /** The one API whose handler needs the service's own session table. */
    const val SESSION_API_NAME = "session"

    /**
     * APIs the service constructs and registers itself, because they need state only
     * the service holds.
     *
     * `session` needs the session table; the USB trio needs the `UsbBroker`, which
     * owns the open device descriptors; `sms_received` needs a registered receiver
     * with a start/stop lifecycle. They are listed here — not in [handlers] — so
     * [names] still carries them for `apis.txt`, while the service is what builds
     * them with their dependency.
     *
     * Declared after [SESSION_API_NAME] deliberately: a `const val` used in another
     * property's initializer has to be initialised first, and Kotlin enforces that
     * in declaration order.
     */
    val serviceApiNames: List<String> =
        listOf(
            SESSION_API_NAME,
            UsbStateApi.NAME,
            UsbAttachApi.NAME,
            UsbDetachApi.NAME,
            SmsReceivedApi.NAME,
            BluetoothControlApi.NAME,
        )

    /**
     * Every API name the bridge serves, including the ones registered separately.
     *
     * `apis.txt` is written from this, so it must be the full set rather than the
     * map's keys — a missing name here is a command the container does not get.
     */
    val names: List<String> = (handlers.keys + serviceApiNames).sorted()

    /**
     * Registers every handler on `router`.
     *
     * The service calls this, then registers the [serviceApiNames] itself — those
     * that need state the service owns.
     */
    fun registerAll(router: ApiRouter): ApiRouter {
        for ((name, handler) in handlers) {
            router.register(name, handler)
        }

        return router
    }
}
