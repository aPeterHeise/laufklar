package de.laufklar.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

data class SensorDevice(val address:String,val name:String,val strength:Int)
@SuppressLint("MissingPermission")
class HeartRateSensor(private val context:Context, private val sample:(Int)->Unit) {
    companion object {
        val SERVICE:UUID=UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT:UUID=UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCC:UUID=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    val devices=MutableStateFlow<List<SensorDevice>>(emptyList())
    val status=MutableStateFlow("Fitbit Air noch nicht verbunden")
    val connected=MutableStateFlow(false)
    private val handler=Handler(Looper.getMainLooper())
    private val adapter get()=context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt:BluetoothGatt?=null
    private var address:String?=null
    private var stopped=false
    private var retries=0
    private var scanning=false
    private val stopScan=Runnable{stopScan();if(devices.value.isEmpty())status.value="Kein Pulssensor gefunden. In Google Health › Fitbit Air › Herzfrequenz teilen aktivieren."}
    private val retry=Runnable{address?.let{connectInternal(it)}}
    private val timeout=Runnable{if(!connected.value){status.value="Verbindung wartet zu lange. Prüfe die Freigabe in Google Health.";gatt?.close();gatt=null;reconnect()}}
    fun permitted()=context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
    fun scan(){
        if(!permitted()){status.value="Bluetooth-Berechtigung fehlt";return}
        if(adapter?.isEnabled!=true){status.value="Bitte Bluetooth einschalten";return}
        stopScan();devices.value=emptyList();status.value="Suche nach Pulssensoren …"
        try{adapter?.bluetoothLeScanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);scanning=true;handler.postDelayed(stopScan,15000)}catch(e:Exception){status.value="Bluetooth-Suche fehlgeschlagen"}
    }
    fun stopScan(){handler.removeCallbacks(stopScan);if(scanning && permitted())runCatching{adapter?.bluetoothLeScanner?.stopScan(scanCallback)};scanning=false}
    private val scanCallback=object:ScanCallback(){
        override fun onScanResult(type:Int,result:ScanResult){handler.post{
            val d=SensorDevice(result.device.address,result.scanRecord?.deviceName?:result.device.name?:"Bluetooth-Pulssensor",result.rssi)
            devices.value=(devices.value.filterNot{it.address==d.address}+d).sortedByDescending{it.strength}
        }}
        override fun onScanFailed(errorCode:Int){handler.post{status.value="Bluetooth-Suche fehlgeschlagen ($errorCode)";scanning=false}}
    }
    fun connect(addr:String){stopScan();stopped=false;address=addr;retries=0;handler.removeCallbacks(retry);connectInternal(addr)}
    private fun connectInternal(addr:String){
        if(stopped)return
        if(!permitted()||adapter?.isEnabled!=true){status.value="Bluetooth oder Berechtigung fehlt";return}
        connected.value=false;gatt?.close();gatt=null;status.value="Verbinde Pulssensor …"
        try{gatt=adapter?.getRemoteDevice(addr)?.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);handler.removeCallbacks(timeout);handler.postDelayed(timeout,25000)}catch(e:Exception){status.value="Pulssensor konnte nicht verbunden werden";reconnect()}
    }
    private fun reconnect(){if(stopped)return;retries++;if(retries<=8){handler.removeCallbacks(retry);handler.postDelayed(retry,(retries*3000L).coerceAtMost(15000));status.value="Pulsverbindung unterbrochen · neuer Versuch $retries/8"}else status.value="Pulsverbindung getrennt. Bitte erneut verbinden."}
    private fun subscribe(g:BluetoothGatt){
        val c=g.getService(SERVICE)?.getCharacteristic(MEASUREMENT)
        val descriptor=c?.getDescriptor(CCC)
        if(c==null||descriptor==null){status.value="Kein Bluetooth-Herzfrequenzdienst verfügbar";g.disconnect();return}
        if(!g.setCharacteristicNotification(c,true)){status.value="Puls-Benachrichtigung nicht verfügbar";g.disconnect();return}
        val result=g.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if(result!=BluetoothStatusCodes.SUCCESS){status.value="Puls-Freigabe fehlgeschlagen ($result)";g.disconnect()}
    }
    private val callback=object:BluetoothGattCallback(){
        override fun onConnectionStateChange(g:BluetoothGatt,statusCode:Int,newState:Int){handler.post{
            if(g!==gatt)return@post
            if(statusCode==BluetoothGatt.GATT_SUCCESS&&newState==BluetoothProfile.STATE_CONNECTED){status.value="Prüfe Puls-Freigabe …";g.discoverServices()}
            else if(newState==BluetoothProfile.STATE_DISCONNECTED||statusCode!=BluetoothGatt.GATT_SUCCESS){connected.value=false;handler.removeCallbacks(timeout);g.close();gatt=null;reconnect()}
        }}
        override fun onServicesDiscovered(g:BluetoothGatt,statusCode:Int){handler.post{if(g!==gatt)return@post;if(statusCode==BluetoothGatt.GATT_SUCCESS)subscribe(g)else g.disconnect()}}
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,statusCode:Int){handler.post{
            if(g!==gatt)return@post
            if(statusCode==BluetoothGatt.GATT_SUCCESS){connected.value=true;retries=0;handler.removeCallbacks(timeout);status.value="Verbunden · warte auf Live-Puls"}
            else if(statusCode==BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION||statusCode==BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION){status.value="Pairing am Telefon / Fitbit bestätigen, dann erneut verbinden";g.device.createBond()}
            else {status.value="Puls-Freigabe abgelehnt ($statusCode)";g.disconnect()}
        }}
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){
            if(c.uuid==MEASUREMENT)handler.post{if(g===gatt){HeartRatePacket.parse(value)?.let{status.value="Live-Puls verbunden";sample(it)}}}
        }
    }
    fun close(){stopped=true;handler.removeCallbacks(retry);handler.removeCallbacks(timeout);stopScan();connected.value=false;if(permitted()){runCatching{gatt?.disconnect();gatt?.close()}};gatt=null;status.value="Pulssensor getrennt"}
}
