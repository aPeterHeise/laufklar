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
class HeartRateSensor(context:Context, private val sample:(Int)->Unit, private val signalLost:()->Unit = {}) {
    private val context=context.applicationContext
    companion object {
        val SERVICE:UUID=UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val MEASUREMENT:UUID=UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCC:UUID=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    val devices=MutableStateFlow<List<SensorDevice>>(emptyList())
    val savedDevice=MutableStateFlow<SensorDevice?>(null)
    val status=MutableStateFlow("Fitbit Air noch nicht verbunden")
    val scanStatus=MutableStateFlow("")
    val connected=MutableStateFlow(false)
    private val handler=Handler(Looper.getMainLooper())
    private val adapter get()=context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt:BluetoothGatt?=null
    private var address:String?=null
    private var stopped=true
    private var pendingConnect=false
    private var retries=0
    private var scanCallback:ScanCallback?=null
    private var scanEpoch=0
    private val closing=mutableMapOf<BluetoothGatt,Release>()
    private val stopScanTask=Runnable{
        stopScan()
        scanStatus.value=if(devices.value.isEmpty()){
            if(savedDevice.value!=null)"Kein neuer Sensor gefunden. Den gespeicherten Sensor kannst du direkt verbinden."
            else "Kein Pulssensor gefunden. In Google Health › Fitbit Air › Herzfrequenz teilen aktivieren."
        }else "Suche abgeschlossen"
    }
    private val retry=Runnable{pendingConnect=true;openWhenReady()}
    private val timeout=Runnable{if(!connected.value){releaseCurrent();reconnect()}}
    fun rememberDevice(addr:String?,name:String?){savedDevice.value=addr?.let{SensorDevice(it,name?:"Fitbit Air",0)}}
    fun permitted()=context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
    fun scan(){
        if(!permitted()){scanStatus.value="Bluetooth-Berechtigung fehlt";return}
        if(adapter?.isEnabled!=true){scanStatus.value="Bitte Bluetooth einschalten";return}
        stopScan();devices.value=emptyList();scanStatus.value="Suche nach weiteren Pulssensoren …"
        val epoch=scanEpoch
        val callback=object:ScanCallback(){
            override fun onScanResult(type:Int,result:ScanResult){handler.post{
                if(epoch!=scanEpoch||scanCallback!==this)return@post
                val name=runCatching{result.scanRecord?.deviceName?:result.device.name}.getOrNull()?:"Bluetooth-Pulssensor"
                val d=SensorDevice(result.device.address,name,result.rssi)
                devices.value=(devices.value.filterNot{it.address==d.address}+d).sortedByDescending{it.strength}
            }}
            override fun onScanFailed(errorCode:Int){handler.post{
                if(epoch!=scanEpoch)return@post
                stopScan();scanStatus.value="Bluetooth-Suche fehlgeschlagen ($errorCode)"
            }}
        }
        try{
            scanCallback=callback
            adapter?.bluetoothLeScanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback)
            handler.postDelayed(stopScanTask,15000)
        }catch(e:Exception){stopScan();scanStatus.value="Bluetooth-Suche fehlgeschlagen"}
    }
    fun stopScan(){
        handler.removeCallbacks(stopScanTask);scanEpoch++
        val callback=scanCallback;scanCallback=null
        if(callback!=null)runCatching{adapter?.bluetoothLeScanner?.stopScan(callback)}
    }
    fun connect(addr:String){
        if(!stopped&&address==addr&&(gatt!=null||pendingConnect))return
        stopScan();stopped=false;address=addr;retries=0;pendingConnect=true
        handler.removeCallbacks(retry);handler.removeCallbacks(timeout)
        releaseCurrent();openWhenReady()
    }
    private fun openWhenReady(){
        if(stopped||!pendingConnect||gatt!=null||closing.isNotEmpty())return
        if(!permitted()||adapter?.isEnabled!=true){pendingConnect=false;status.value="Bluetooth oder Berechtigung fehlt";return}
        val addr=address?:return
        pendingConnect=false;connected.value=false;signalLost();status.value="Verbinde gespeicherten Pulssensor …"
        try{
            gatt=adapter?.getRemoteDevice(addr)?.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE)
            if(gatt==null)reconnect()else handler.postDelayed(timeout,25000)
        }catch(e:Exception){reconnect()}
    }
    private fun reconnect(){
        if(stopped)return
        retries++
        if(retries<=8){handler.removeCallbacks(retry);handler.postDelayed(retry,(retries*3000L).coerceAtMost(15000));status.value="Pulsverbindung unterbrochen · neuer Versuch $retries/8"}
        else status.value="Pulsverbindung getrennt. Bitte erneut verbinden."
    }
    private fun subscribe(g:BluetoothGatt){
        val c=g.getService(SERVICE)?.getCharacteristic(MEASUREMENT)
        val descriptor=c?.getDescriptor(CCC)
        if(c==null||descriptor==null){fail("Kein Bluetooth-Herzfrequenzdienst verfügbar");return}
        try{
            if(!g.setCharacteristicNotification(c,true)){fail("Puls-Benachrichtigung nicht verfügbar");return}
            val result=g.writeDescriptor(descriptor,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if(result!=BluetoothStatusCodes.SUCCESS)fail("Puls-Freigabe fehlgeschlagen ($result)")
        }catch(e:Exception){fail("Puls-Freigabe fehlgeschlagen")}
    }
    private fun fail(message:String){releaseCurrent();reconnect();status.value=message}

    // Drain notifications, disconnect, then unregister the GATT client. A bounded fallback
    // handles missing callbacks. New clients wait until all previous clients have closed.
    private inner class Release(val client:BluetoothGatt){
        var disconnectStarted=false
        val disconnect=Runnable{
            if(!disconnectStarted){disconnectStarted=true;runCatching{client.disconnect()}.onFailure{finishRelease(client)}}
        }
        val forceClose=Runnable{disconnect.run();finishRelease(client)}
    }
    private fun releaseCurrent(){
        handler.removeCallbacks(timeout)
        val old=gatt;gatt=null
        val wasSubscribed=connected.value;connected.value=false;signalLost()
        if(old==null)return
        val release=Release(old);closing[old]=release
        handler.postDelayed(release.forceClose,1500)
        val c=runCatching{old.getService(SERVICE)?.getCharacteristic(MEASUREMENT)}.getOrNull()
        val descriptor=c?.getDescriptor(CCC)
        runCatching{if(c!=null)old.setCharacteristicNotification(c,false)}
        val disabling=wasSubscribed&&descriptor!=null&&runCatching{old.writeDescriptor(descriptor,BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS}.getOrDefault(false)
        if(disabling)handler.postDelayed(release.disconnect,300)else release.disconnect.run()
    }
    private fun finishRelease(client:BluetoothGatt){
        val release=closing.remove(client)?:return
        handler.removeCallbacks(release.disconnect);handler.removeCallbacks(release.forceClose)
        runCatching{client.close()}
        openWhenReady()
    }
    private val callback=object:BluetoothGattCallback(){
        override fun onConnectionStateChange(g:BluetoothGatt,statusCode:Int,newState:Int){handler.post{
            if(closing.containsKey(g)){
                if(newState==BluetoothProfile.STATE_DISCONNECTED)finishRelease(g)
                return@post
            }
            if(g!==gatt)return@post
            if(statusCode==BluetoothGatt.GATT_SUCCESS&&newState==BluetoothProfile.STATE_CONNECTED){
                status.value="Prüfe Puls-Freigabe …"
                if(!runCatching{g.discoverServices()}.getOrDefault(false))fail("Pulsdienst konnte nicht gelesen werden")
            }else if(newState==BluetoothProfile.STATE_DISCONNECTED||statusCode!=BluetoothGatt.GATT_SUCCESS){
                handler.removeCallbacks(timeout);gatt=null;connected.value=false;signalLost();runCatching{g.close()};reconnect()
            }
        }}
        override fun onServicesDiscovered(g:BluetoothGatt,statusCode:Int){handler.post{
            if(g!==gatt)return@post
            if(statusCode==BluetoothGatt.GATT_SUCCESS)subscribe(g)else fail("Pulsdienst konnte nicht gelesen werden")
        }}
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,statusCode:Int){handler.post{
            closing[g]?.let{it.disconnect.run();return@post}
            if(g!==gatt||d.uuid!=CCC)return@post
            if(statusCode==BluetoothGatt.GATT_SUCCESS){connected.value=true;retries=0;handler.removeCallbacks(timeout);status.value="Verbunden · warte auf Live-Puls"}
            else if(statusCode==BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION||statusCode==BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION){
                status.value="Pairing am Telefon / Fitbit bestätigen";runCatching{g.device.createBond()}
            }else fail("Puls-Freigabe abgelehnt ($statusCode)")
        }}
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){
            if(c.uuid==MEASUREMENT)handler.post{if(g===gatt&&!stopped){HeartRatePacket.parse(value)?.let{status.value="Live-Puls verbunden";sample(it)}}}
        }
    }
    fun close(){
        stopped=true;pendingConnect=false
        handler.removeCallbacks(retry);handler.removeCallbacks(timeout);stopScan();releaseCurrent()
        status.value="Pulssensor freigegeben"
    }
}
