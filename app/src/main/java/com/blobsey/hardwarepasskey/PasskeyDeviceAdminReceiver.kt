package com.blobsey.hardwarepasskey

import android.app.admin.DeviceAdminReceiver

/**
 * Empty [DeviceAdminReceiver] used solely to enable uninstall protection
 * No policy overrides are needed, the base class handles everything
 */
class PasskeyDeviceAdminReceiver : DeviceAdminReceiver()
