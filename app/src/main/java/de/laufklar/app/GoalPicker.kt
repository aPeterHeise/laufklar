package de.laufklar.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.roundToInt

@Composable fun GoalPicker(profile:Profile,onDismiss:()->Unit,onAccept:(Profile)->Unit,useReferenceInitially:Boolean=false){
    val context=LocalContext.current
    var age by remember{mutableStateOf(profile.age.toString())}
    var sex by remember{mutableStateOf(profile.sex)}
    var distance by remember{mutableDoubleStateOf(profile.goalKm)}
    var percent by remember{mutableDoubleStateOf(if(useReferenceInitially)100.0 else ((ResearchNorms.reference(profile.age,profile.sex)?.first?:profile.goalPace.toDouble())/profile.goalPace*100).coerceIn(80.0,120.0))}
    val ageNumber=age.toIntOrNull()
    val reference=ageNumber?.let{ResearchNorms.reference(it,sex)}
    val target=reference?.let{ResearchNorms.adjustedPace(it.first,percent)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=Ink){
            Column(Modifier.fillMaxSize().safeDrawingPadding()){
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Eyebrow("DEIN ZIEL. DEIN SPIEL.",Lime);IconButton(onClick=onDismiss){Icon(Icons.Outlined.Close,"Zielauswahl schließen")}}
                Box(Modifier.weight(1f)){
                    Page{
                        Text("Wohin möchtest\ndu laufen?",fontSize=34.sp,lineHeight=38.sp,fontWeight=FontWeight.SemiBold)
                        Text("Ein langfristiger Anker für deinen Trainer. Kein Countdown, kein Ablaufdatum.",color=Muted)
                        Field("Dein Alter",age,{age=it;percent=100.0})
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){listOf("Männlich","Weiblich").forEach{s->FilterChip(selected=sex==s,onClick={sex=s;percent=100.0},label={Text(s)})}}
                        Text("Die Studie berichtet diese zwei Vergleichsgruppen. Ohne passende Gruppe kannst du im Profil ein eigenes Ziel frei eingeben.",color=Muted,fontSize=12.sp)
                        if(reference==null){CardBlock{Text("Kein verlässlicher Altersvergleich",fontWeight=FontWeight.SemiBold);Text("Für diese Auswahl verwenden wir keine kleine oder fehlende Vergleichsgruppe. Lege im Profil Distanz und Zielzeit selbst fest.",color=Muted);OutlinedButton(onClick=onDismiss){Text("Eigenes Ziel eingeben")}}}
                        else{
                            Eyebrow("Wähle deine Distanz")
                            listOf(5.0 to "Die kompakte Runde",10.0 to "Deine Ausdauer-Routine",21.1 to "Die lange Herausforderung").forEach{(km,label)->
                                val seconds=km*target!!
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if(distance==km)Lime.copy(alpha=.12f)else Panel).clickable{distance=km}.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
                                    RadioButton(selected=distance==km,onClick={distance=km});Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)){Text("${"%.1f".format(Locale.GERMANY,km)} km",fontSize=23.sp,fontWeight=FontWeight.SemiBold);Text(label,color=Muted,fontSize=12.sp)}
                                    Text(Metrics.duration(seconds),color=if(distance==km)Lime else Cream,fontSize=23.sp)
                                }
                            }
                            CardBlock{
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Eyebrow("Dein Tempofaktor");Text("${percent.roundToInt()} %",fontSize=30.sp,color=Lime,fontWeight=FontWeight.SemiBold)}
                                Slider(value=percent.toFloat(),onValueChange={percent=it.toDouble()},valueRange=80f..120f,steps=39)
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Entspannter",color=Muted,fontSize=12.sp);Text("Mehr Herausforderung",color=Muted,fontSize=12.sp)}
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(90,100,110).forEach{pct->FilterChip(selected=percent.roundToInt()==pct,onClick={percent=pct.toDouble()},label={Text("$pct %")})}}
                                Row(Modifier.fillMaxWidth()){Stat(Metrics.pace(target!!.toDouble()),"Zielpace · min/km",Lime);Stat(Metrics.duration(distance*target),"Zielzeit")}
                                Text("100 % = Referenzgeschwindigkeit deiner Altersgruppe. 110 % = 10 % schneller; deshalb sinkt die min/km-Pace. Der Trainer nähert sich diesem Ziel schrittweise.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
                            }
                            CardBlock{
                                Eyebrow("Aus Forschung wird dein Ziel")
                                Text("${ageNumber} Jahre · $sex · Referenz ${Metrics.pace(reference.first)} min/km (Median, n=${reference.second})",fontWeight=FontWeight.SemiBold)
                                Text("Selbstberichtete Bestpace brasilianischer Freizeitläufer, über verschiedene bevorzugte Distanzen. Die App überträgt diesen Altersanker auf deine gewählte Distanz. Das ist unser Zielvorschlag, keine von der Studie geprüfte Leistungsprognose oder gesundheitliche Mindestanforderung.",color=Muted,fontSize=12.sp,lineHeight=19.sp)
                                ResearchNorms.distanceReference(distance,sex)?.let{pace->Text("Zusätzlicher Vergleich: Bei ${"%.1f".format(Locale.GERMANY,distance)} km lag der Studienmedian für $sex über alle Altersgruppen bei ${Metrics.pace(pace)} min/km. Diese separate Zahl wird nicht als gemeinsame Alters-/Distanznorm ausgegeben.",color=Muted,fontSize=12.sp,lineHeight=19.sp)}
                                TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://pmc.ncbi.nlm.nih.gov/articles/PMC8304551/")))}){Text("Studie & Originaltabellen öffnen ↗")}
                                Text("Thuany, Knechtle, Hill, Rosemann & Gomes (2021), Healthcare 9(7), 829. Daten aus Tabellen 2–3, CC BY 4.0. Auswahl, Darstellung und Temporegelung: Laufklar. Kleine Altersgruppen werden nicht als Vorschlag verwendet.",color=Muted,fontSize=11.sp,lineHeight=17.sp)
                            }
                        }
                    }
                }
                Column(Modifier.background(Ink).padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    FullButton("Als dauerhaftes Laufziel übernehmen",Icons.Outlined.Flag,enabled=target!=null&&ageNumber!=null&&ageNumber in 18..100){
                        if(target!=null&&ageNumber!=null)onAccept(profile.copy(age=ageNumber,sex=sex,goalKm=distance,goalPace=target))
                    }
                    Text("Jederzeit änderbar · dein Verlauf bleibt erhalten",fontSize=11.sp,color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
}
