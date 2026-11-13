package de.laufklar.app

import android.content.Context
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.CopyrightOverlay
import java.io.File
import kotlin.math.hypot

@Composable
@SuppressLint("ClickableViewAccessibility") // Observes dragging only; MapView still handles every touch and accessibility click.
fun RouteMap(points:List<TrackPoint>,online:Boolean,modifier:Modifier=Modifier,followPosition:Boolean=false,pulses:List<Pulse> = emptyList(),zoneBounds:List<Int> = Profile().bounds){
    var view by remember{mutableStateOf<MapView?>(null)}
    var following by rememberSaveable(followPosition){mutableStateOf(followPosition)}
    val camera=remember{RouteCamera()}
    DisposableEffect(Unit){onDispose{view?.onPause();view?.onDetach()}}
    Box(modifier){
        AndroidView(modifier=Modifier.fillMaxSize(),factory={context:Context->
            Configuration.getInstance().apply{
                userAgentValue="Laufklar/0.1 (personal Android running tracker)"
                osmdroidBasePath=File(context.cacheDir,"maps");osmdroidTileCache=File(context.cacheDir,"maps/tiles")
                expirationExtendedDuration=7*24*60*60*1000L
            }
            MapView(context).apply{
                setTileSource(TileSourceFactory.MAPNIK);setMultiTouchControls(true);setUseDataConnection(online)
                controller.setZoom(if(followPosition)17.0 else 15.0)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
                var downX=0f;var downY=0f
                val slop=ViewConfiguration.get(context).scaledTouchSlop
                setOnTouchListener{_,event->
                    when(event.actionMasked){
                        MotionEvent.ACTION_DOWN->{downX=event.x;downY=event.y;parent?.requestDisallowInterceptTouchEvent(true)}
                        MotionEvent.ACTION_MOVE->if(hypot((event.x-downX).toDouble(),(event.y-downY).toDouble())>slop&&following){following=false;controller.stopAnimation(false)}
                        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                onResume();view=this
            }
        },update={map->
            map.setUseDataConnection(online)
            if(camera.drawnPoints!==points||camera.drawnPulses!==pulses||camera.drawnBounds!=zoneBounds){
                camera.drawnPoints=points
                camera.drawnPulses=pulses;camera.drawnBounds=zoneBounds
                map.overlays.clear()
                zoneRouteParts(points,pulses,zoneBounds).forEach{part->
                    val coordinates=part.points.map{GeoPoint(it.lat,it.lon)}
                    val border=Polyline(map);border.setPoints(coordinates);border.outlinePaint.color=android.graphics.Color.DKGRAY;border.outlinePaint.strokeWidth=14f;map.overlays.add(border)
                    val line=Polyline(map);line.setPoints(coordinates)
                    line.outlinePaint.color=(part.zone?.let{ZoneColors[it]}?:UnknownZoneColor).toArgb();line.outlinePaint.strokeWidth=10f
                    map.overlays.add(line)
                }
                points.firstOrNull()?.let{p->map.overlays.add(Marker(map).apply{position=GeoPoint(p.lat,p.lon);title="Start";setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)})}
                points.lastOrNull()?.let{p->map.overlays.add(Marker(map).apply{position=GeoPoint(p.lat,p.lon);title=if(followPosition)"Aktuelle Position"else "Ziel / letzter Punkt";setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)})}
                map.overlays.add(CopyrightOverlay(map.context).apply{setTextSize(13);setTextColor(android.graphics.Color.BLACK);setAlignBottom(true)})
            }
            val last=points.lastOrNull()
            if(followPosition&&following&&last!=null){
                if(last!=camera.lastCentered||!camera.wasFollowing){
                    val target=GeoPoint(last.lat,last.lon)
                    if(!camera.framed){map.controller.setCenter(target);camera.framed=true}
                    else map.controller.animateTo(target)
                    camera.lastCentered=last
                }
            }else if(!followPosition&&!camera.framed&&points.isNotEmpty()){
                camera.framed=true
                map.post{
                    if(points.size>1)map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points.map{GeoPoint(it.lat,it.lon)}),false,48,17.0,0L)
                    else map.controller.setCenter(GeoPoint(points[0].lat,points[0].lon))
                }
            }
            camera.wasFollowing=following
            map.invalidate()
        })
        if(followPosition)IconButton(onClick={following=!following},modifier=Modifier.align(Alignment.TopEnd).padding(8.dp).background(Ink.copy(alpha=.9f),CircleShape)){
            Icon(Icons.Outlined.MyLocation,if(following)"Position wird gefolgt"else "Position folgen",tint=if(following)Lime else Cream)
        }
    }
}
private class RouteCamera {
    var framed=false
    var wasFollowing=false
    var lastCentered:TrackPoint?=null
    var drawnPoints:List<TrackPoint>?=null
    var drawnPulses:List<Pulse>?=null
    var drawnBounds:List<Int>?=null
}
val UnknownZoneColor=Color(0xFF9A9A9A)
@Composable fun RouteZoneLegend(){
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            (1..5).forEach{zone->Text("● Z$zone",color=ZoneColors[zone],fontSize=12.sp)}
        }
        Text("● Unter Z1",color=ZoneColors[0],fontSize=11.sp)
        Text("● Ohne aktuellen Puls · grau",color=UnknownZoneColor,fontSize=11.sp)
    }
}
