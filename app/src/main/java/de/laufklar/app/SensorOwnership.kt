package de.laufklar.app

/** A preview needs a visible app; a recording owns the connection independently. */
class SensorOwnership(private val connect:(String)->Unit, private val release:()->Unit) {
    private var address:String?=null
    private var activeAddress:String?=null
    private val visibleScreens=mutableSetOf<Any>()
    private val previewScreens=mutableSetOf<Any>()
    val hasVisiblePreview get()=visibleScreens.any{it in previewScreens}
    private var recording=false
    private var suspended=false
    fun device(value:String?){address=value;update()}
    fun choose(value:String){address=value;suspended=false;if(activeAddress==value)reconnect()else update()}
    fun visible(value:Boolean,screen:Any=Unit){
        val before=hasVisiblePreview
        if(value)visibleScreens.add(screen)else visibleScreens.remove(screen)
        if(!before&&hasVisiblePreview)suspended=false
        update()
    }
    fun preview(value:Boolean,screen:Any=Unit){
        val before=hasVisiblePreview
        if(value)previewScreens.add(screen)else previewScreens.remove(screen)
        if(!before&&hasVisiblePreview)suspended=false
        update()
    }
    fun recording(value:Boolean){if(value&&!recording)suspended=false;recording=value;update()}
    fun reconnect(){suspended=false;if(activeAddress!=null){activeAddress=null;release()};update()}
    fun disconnect(){suspended=true;update()}
    private fun update(){
        val wanted=address.takeIf{!suspended&&(recording||hasVisiblePreview)}
        if(wanted==activeAddress)return
        if(activeAddress!=null){activeAddress=null;release()}
        if(wanted!=null){activeAddress=wanted;connect(wanted)}
    }
}
