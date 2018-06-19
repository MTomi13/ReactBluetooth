package com.marton.tamas.reactbluetooth

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import com.github.ivbaranov.rxbluetooth.RxBluetooth
import io.reactivex.Flowable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import java.io.IOException
import java.util.*


@Suppress("PrivatePropertyName")
class MainActivity : AppCompatActivity() {

    private val REQUEST_PERMISSION_COARSE_LOCATION = 0
    private val REQUEST_ENABLE_BLUETOOTH = 1
    private val MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private lateinit var rxBluetooth: RxBluetooth
    private val compositeDisposable = CompositeDisposable()
    private val devices = ArrayList<BluetoothDevice>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar.title = getString(R.string.app_name)

        rxBluetooth = RxBluetooth(this)

        if (!rxBluetooth.isBluetoothAvailable) {
            showLog("Bluetooth is not supported!")
        } else {
            initListeners()
            if (!rxBluetooth.isBluetoothEnabled) {
                showLog("Enabling Bluetooth")
                rxBluetooth.enableBluetooth(this, REQUEST_ENABLE_BLUETOOTH)
            } else {
                start.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.colorActive))
            }
        }
    }

    private fun initListeners() {
        observeDevicesWithBluetooth()
        observeDiscoveryStatus()
        observeBluetoothStatus()

        start.setOnClickListener({
            startSearchForDevices()
        })
        stop.setOnClickListener({
            stopDiscovery()
        })
    }

    private fun showLog(text: String) {
        Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
    }

    private fun observeDevicesWithBluetooth() {
        compositeDisposable.add(rxBluetooth.observeDevices()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe { bluetoothDevice -> addDevice(bluetoothDevice) })
    }

    private fun addDevice(device: BluetoothDevice) {
        devices.add(device)
        setupAdapter(devices)
    }

    private fun observeDiscoveryStatus() {
        compositeDisposable.add(rxBluetooth.observeDiscovery()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe { action ->
                    if (action == BluetoothAdapter.ACTION_DISCOVERY_STARTED) {
                        start.setText(R.string.button_searching)
                    } else if (action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                        start.setText(R.string.button_restart)
                    }
                })
    }

    private fun observeBluetoothStatus() {
        compositeDisposable.add(rxBluetooth.observeBluetoothState()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeOn(Schedulers.computation())
                .subscribe { integer ->
                    when (integer) {
                        BluetoothAdapter.STATE_ON -> start.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.colorActive))
                        BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> start.setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.colorInactive))
                    }
                })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_COARSE_LOCATION) {
            for (permission in permissions) {
                if (ACCESS_COARSE_LOCATION == permission) {
                    rxBluetooth.startDiscovery()
                }
            }
        }
    }

    private fun startSearchForDevices() {
        if (!rxBluetooth.isBluetoothEnabled) {
            rxBluetooth.enableBluetooth(this@MainActivity, REQUEST_ENABLE_BLUETOOTH)
        }
        devices.clear()
        setupAdapter(devices)
        checkPermission()
    }

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this@MainActivity, ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this@MainActivity,
                    arrayOf(ACCESS_COARSE_LOCATION),
                    REQUEST_PERMISSION_COARSE_LOCATION)
        } else {
            rxBluetooth.startDiscovery()
        }
    }

    private fun stopDiscovery() {
        rxBluetooth.cancelDiscovery()
    }

    private fun setupAdapter(list: List<BluetoothDevice>) {
        result.adapter = object : ArrayAdapter<BluetoothDevice>(this, android.R.layout.simple_list_item_2, android.R.id.text1, list) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)

                with(devices[position]) {
                    var devName = name

                    if (TextUtils.isEmpty(devName)) {
                        devName = "NO NAME"
                    }
                    view.findViewById<TextView>(android.R.id.text1).text = devName
                    view.findViewById<TextView>(android.R.id.text2).text = address

                    view.setOnClickListener {
                        showDialog(this)
                    }
                }
                return view
            }
        }
    }

    private fun showDialog(bluetoothDevice: BluetoothDevice) {
        AlertDialog.Builder(this@MainActivity)
                .setTitle("Connect to device: " + bluetoothDevice.address)
                .setCancelable(false)
                .setNegativeButton("Cancel", { dialog, _ ->
                    dialog.dismiss()
                })
                .setPositiveButton("Ok", { dialog, _ ->
                    try {
                        dialog.dismiss()
                        showLog("Connection is creating, please wait....")
                        compositeDisposable.add(connectBlueTooth(bluetoothDevice)
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribeOn(Schedulers.io())
                                .subscribe {
                                    showLog("Connection is created, enjoy!")
                                })

                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }).show()
    }

    private fun connectBlueTooth(bluetoothDevice: BluetoothDevice): Flowable<Unit> {
        return Flowable.just(createBluetoothSocket(bluetoothDevice).connect())
    }

    @Throws(IOException::class)
    private fun createBluetoothSocket(device: BluetoothDevice): BluetoothSocket {
        if (Build.VERSION.SDK_INT >= 15) {
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                return method.invoke(device, 2) as BluetoothSocket
            } catch (e: Exception) {
                showLog("Could not create RFComm Connection")
            }
        }
        return device.createRfcommSocketToServiceRecord(MY_UUID)
    }

    override fun onDestroy() {
        super.onDestroy()
        rxBluetooth.cancelDiscovery()
        compositeDisposable.dispose()
    }
}