package de.laufklar.app

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@Composable
fun RouteMap(points:List<TrackPoint>,online:Boolean,modifier:Modifier=Modifier){
    var view by remember{mutableStateOf<MapView?>(null)}
    var framed by remember{mutableStateOf(false)}
    DisposableEffect(Unit){onDispose{view?.onPause();view?.onDetach()}}
    AndroidView(modifier=modifier,factory={context:Context->
        Configuration.getInstance().apply{
            userAgentValue="Laufklar/0.1 (personal Android running tracker)"
            osmdroidBasePath=File(context.cacheDir,"maps");osmdroidTileCache=File(context.cacheDir,"maps/tiles")
            expirationExtendedDuration=7*24*60*60*1000L
        }
        MapView(context).apply{
            setTileSource(TileSourceFactory.MAPNIK);setMultiTouchControls(true);setUseDataConnection(online)
            controller.setZoom(15.0);zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            onResume();view=this
        }
    },update={map->
        map.setUseDataConnection(online);map.overlays.clear()
        points.groupBy{it.segment}.values.forEach{part->
            if(part.size>1){val line=Polyline(map);line.setPoints(part.map{GeoPoint(it.lat,it.lon)});line.outlinePaint.color=android.graphics.Color.rgb(56,113,45);line.outlinePaint.strokeWidth=10f;map.overlays.add(line)}
        }
        points.firstOrNull()?.let{p->map.overlays.add(Marker(map).apply{position=GeoPoint(p.lat,p.lon);title="Start";setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)})}
        points.lastOrNull()?.let{p->map.overlays.add(Marker(map).apply{position=GeoPoint(p.lat,p.lon);title="Ziel / letzter Punkt";setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)})}
        map.overlays.add(CopyrightOverlay(map.context).apply{setTextSize(13);setTextColor(android.graphics.Color.BLACK);setAlignBottom(true)})
        if(!framed&&points.isNotEmpty()){
            framed=true
            map.post{
                if(points.size>1)map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points.map{GeoPoint(it.lat,it.lon)}),false,48,17.0,0L)
                else map.controller.setCenter(GeoPoint(points[0].lat,points[0].lon))
            }
        };map.invalidate()
    })
}
