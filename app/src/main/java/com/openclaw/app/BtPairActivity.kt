package com.openclaw.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.app.Activity

/**
 * Headless activity that pairs with a specific BT device, then finishes.
 * Launch via: adb shell am start -n com.openclaw.app/.BtPairActivity --es mac "A0:6B:4A:59:04:F4"
 *
 * Also auto-accepts pairing confirmations for RayNeo devices.
 */
@SuppressLint("MissingPermission")
class BtPairActivity : Activity() {

    companion object {
        private const val TAG = "BtPairActivity"
    }

    private var receiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetMac = intent.getStringExtra("mac")
        Log.i(TAG, "Pairing activity started, target=$targetMac")

        // Register auto-accept receiver
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                        Log.i(TAG, "Pairing request: ${device?.name} (${device?.address}) variant=$variant")

                        try {
                            // Auto-confirm for all pairing types
                            when (variant) {
                                BluetoothDevice.PAIRING_VARIANT_PIN -> {
                                    device?.setPin("0000".toByteArray())
                                    Log.i(TAG, "Set PIN 0000")
                                }
                                BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                                    device?.setPairingConfirmation(true)
                                    Log.i(TAG, "Confirmed passkey")
                                }
                                else -> {
                                    device?.setPairingConfirmation(true)
                                    Log.i(TAG, "Confirmed pairing (variant=$variant)")
                                }
                            }
                            abortBroadcast()
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-confirm failed: ${e.message}")
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                        Log.i(TAG, "Bond: ${device?.name} ${prevState} → $state")
                        if (state == BluetoothDevice.BOND_BONDED) {
                            Log.i(TAG, "*** PAIRED SUCCESSFULLY with ${device?.name} ***")
                            finish()
                        } else if (state == BluetoothDevice.BOND_NONE && prevState == BluetoothDevice.BOND_BONDING) {
                            Log.e(TAG, "Pairing FAILED for ${device?.name}")
                            // Retry once
                            if (targetMac != null) {
                                val adapter = BluetoothAdapter.getDefaultAdapter()
                                val dev = adapter?.getRemoteDevice(targetMac)
                                dev?.createBond()
                            }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(receiver, filter)

        // If target MAC provided, initiate pairing
        if (targetMac != null) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                Log.e(TAG, "BT not available")
                finish()
                return
            }

            val device = adapter.getRemoteDevice(targetMac)
            Log.i(TAG, "Initiating bond with ${device.name ?: targetMac}")
            device.createBond()
        } else {
            // No target — just run as auto-accept receiver for 60s
            Log.i(TAG, "No target MAC — running as auto-accept for 60s")
            window.decorView.postDelayed({ finish() }, 60000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { receiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    }
}
