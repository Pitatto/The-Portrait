package com.noiraube.theportrait

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 20, 26)
        window.navigationBarColor = Color.rgb(15, 20, 26)
        window.decorView.systemUiVisibility = 0
        setContentView(PortraitView())
    }

    inner class PortraitView : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var decayed = false
        private var watchOpen = false
        private val tasks = mutableListOf("Rangement", "Courses", "Courrier", "Préparer la semaine")
        private val checked = mutableSetOf(0)
        private var downX = 0f
        private var downY = 0f
        private var scale = 1f
        private var watchBounds = RectF()
        private var taskBounds = RectF()
        private var iconBounds = mutableListOf<Pair<RectF, AppItem>>()
        private val apps = listOf(
            AppItem("Discord", "com.discord", "D"),
            AppItem("Reddit", "com.reddit.frontpage", "R"),
            AppItem("Instagram", "com.instagram.android", "◎"),
            AppItem("Facebook", "com.facebook.katana", "f"),
            AppItem("YouTube", "com.google.android.youtube", "▶"),
            AppItem("YT Music", "com.google.android.apps.youtube.music", "♫"),
            AppItem("Bandcamp", "com.bandcamp.android", "b"),
            AppItem("Outlook", "com.microsoft.office.outlook", "✉"),
            AppItem("Vinted", "com.vinted", "V"),
            AppItem("Chrome", "com.android.chrome", "◎")
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            scale = width / 420f
            canvas.save()
            canvas.scale(scale, scale)
            drawBackdrop(canvas)
            drawHeader(canvas)
            drawPortrait(canvas)
            drawChecklist(canvas)
            drawPocketWatch(canvas)
            drawApps(canvas)
            drawFooter(canvas)
            if (watchOpen) drawWatchFace(canvas)
            canvas.restore()
        }

        private fun color(hex: String) = Color.parseColor(hex)
        private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, italic: Boolean = false) {
            p.shader = null; p.color = col; p.textSize = size
            p.typeface = if (italic) Typeface.create("serif", Typeface.ITALIC) else Typeface.create("serif", Typeface.NORMAL)
            p.style = Paint.Style.FILL
            c.drawText(s, x, y, p)
        }
        private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, col: Int, sw: Float = 1f) {
            p.shader = null; p.color = col; p.strokeWidth = sw; p.style = Paint.Style.STROKE
            c.drawLine(x1,y1,x2,y2,p); p.style = Paint.Style.FILL
        }
        private fun rr(c: Canvas, r: RectF, radius: Float, fill: Int, stroke: Int? = null) {
            p.shader = null; p.color = fill; p.style = Paint.Style.FILL
            c.drawRoundRect(r, radius, radius, p)
            if (stroke != null) { p.color = stroke; p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f; c.drawRoundRect(r,radius,radius,p); p.style=Paint.Style.FILL }
        }

        private fun drawBackdrop(c: Canvas) {
            p.shader = LinearGradient(0f,0f,420f,850f,
                if (decayed) intArrayOf(color("#080B0E"),color("#282725"),color("#0C1014"))
                else intArrayOf(color("#111820"),color("#303943"),color("#10151B")), null, Shader.TileMode.CLAMP)
            c.drawRect(0f,0f,420f,850f,p); p.shader=null
            // Painterly drapery / broad brush strokes
            for (i in 0..12) {
                val x = i*38f - 25f
                p.color = if (decayed) color(if(i%2==0) "#302B29" else "#17191A")
                           else color(if(i%2==0) "#252D36" else "#171F28")
                p.strokeWidth = 18f + (i%3)*5f; p.style=Paint.Style.STROKE
                c.drawArc(x,-80f,x+95f,620f,95f,175f,false,p)
            }
            p.style=Paint.Style.FILL
            // Moonlit window
            rr(c, RectF(278f,35f,405f,330f), 58f, if(decayed) color("#171A1A") else color("#202A34"), color("#7E8992"))
            p.shader = RadialGradient(340f,112f,112f, if(decayed) color("#6C6A60") else color("#AEB8C0"), color("#34404B"), Shader.TileMode.CLAMP)
            c.drawCircle(344f,118f,80f,p); p.shader=null
            for (x in listOf(341f,382f)) line(c,x,38f,x,328f,color("#6C7780"),3f)
            line(c,280f,190f,404f,190f,color("#6C7780"),3f)
            // distant spires
            for (i in 0..5) {
                val x=280f+i*24f
                p.color=color(if(decayed) "#111414" else "#101820")
                val path=Path().apply { moveTo(x-17,340f); lineTo(x,250f-(i%3)*24); lineTo(x+17,340f); close() }
                c.drawPath(path,p)
            }
            if (decayed) {
                // cracks and cobweb-like lines
                val crack=color("#77736B")
                for (i in 0..7) line(c, 20f+i*53, 40f, 55f+i*43, 170f+(i%3)*70, crack, 1f)
                for (i in 0..5) line(c, 0f, 620f+i*22, 420f, 610f+i*22, color("#292A27"), 2f)
            }
        }

        private fun drawHeader(c: Canvas) {
            val ivory = if(decayed) color("#AAA49A") else color("#D1D2CD")
            text(c, SimpleDateFormat("EEEE", Locale.FRENCH).format(Date()).replaceFirstChar { it.uppercase() }, 25f, 64f, 30f, ivory, true)
            text(c, SimpleDateFormat("dd MMMM", Locale.FRENCH).format(Date()), 25f, 96f, 22f, ivory, true)
            line(c,25f,111f,235f,111f,color("#89949D"),1f)
            text(c,"8°  ·  ciel couvert",27f,140f,16f,ivory)
        }

        private fun drawPortrait(c: Canvas) {
            val frame = RectF(132f,155f,274f,365f)
            rr(c,frame,70f,if(decayed) color("#191B1B") else color("#202832"),color("#9CA6AE"))
            rr(c,RectF(140f,163f,266f,357f),62f,if(decayed) color("#3A3530") else color("#4A5158"),color("#68737D"))
            // symbolic portrait silhouette
            p.color=if(decayed) color("#171514") else color("#20242A")
            c.drawOval(174f,193f,234f,279f,p)
            c.drawOval(161f,260f,247f,344f,p)
            p.color=if(decayed) color("#746A5F") else color("#B3B7B7")
            c.drawOval(190f,206f,219f,252f,p)
            if(decayed) {
                line(c,160f,185f,240f,333f,color("#7E776C"),1.5f)
                line(c,230f,190f,178f,345f,color("#625D55"),1f)
            }
            text(c,"THE PORTRAIT",145f,384f,10f,ivoryColor(),true)
        }
        private fun ivoryColor()=if(decayed) color("#A49E94") else color("#C5C9C9")

        private fun drawChecklist(c: Canvas) {
            taskBounds=RectF(20f,420f,218f,610f)
            rr(c,taskBounds,8f,if(decayed) color("#4B453D") else color("#C4BBAA"),if(decayed) color("#81796D") else color("#E0D9CC"))
            text(c,"À faire",38f,450f,24f,if(decayed) color("#D0C4B0") else color("#33373A"),true)
            line(c,36f,460f,200f,460f,color("#827D75"),1f)
            for(i in tasks.indices) {
                val y=486f+i*29f
                rr(c,RectF(36f,y-14,50f,y),2f,Color.TRANSPARENT,if(decayed) color("#6B6258") else color("#43484A"))
                if(checked.contains(i)) {
                    line(c,38f,y-7,43f,y-2,color("#7B333A"),2.5f)
                    line(c,43f,y-2,51f,y-16,color("#7B333A"),2.5f)
                }
                val label=tasks[i].take(22)
                text(c,label,60f,y,14f,if(decayed) color("#B8AA98") else color("#333638"),true)
                if(decayed && i%2==1) line(c,60f,y-5,155f,y-3,color("#887B6B"),0.7f)
            }
            text(c,"✦",188f,596f,15f,if(decayed) color("#756C61") else color("#7A7070"))
        }

        private fun drawPocketWatch(c: Canvas) {
            val cx=326f; val cy=445f
            watchBounds=RectF(272f,390f,385f,512f)
            // chain
            p.color=if(decayed) color("#66645D") else color("#AEB8BF")
            p.strokeWidth=3f; p.style=Paint.Style.STROKE
            val chain=Path().apply { moveTo(cx,390f); cubicTo(282f,360f,365f,355f,326f,398f) }
            c.drawPath(chain,p); p.style=Paint.Style.FILL
            p.color=if(decayed) color("#56534D") else color("#9DA8B0")
            c.drawCircle(cx,450f,48f,p)
            p.color=if(decayed) color("#222323") else color("#343D46")
            c.drawCircle(cx,450f,42f,p)
            p.color=if(decayed) color("#786F63") else color("#C0C7CB")
            p.style=Paint.Style.STROKE; p.strokeWidth=1.5f; c.drawCircle(cx,450f,37f,p); p.style=Paint.Style.FILL
            text(c,"✥",315f,458f,25f,if(decayed) color("#8A8174") else color("#C6CDD0"))
            if(decayed) {
                line(c,289f,424f,355f,483f,color("#756E65"),1.4f)
                line(c,357f,421f,300f,479f,color("#625E57"),1f)
            }
        }

        private fun drawApps(c: Canvas) {
            iconBounds.clear()
            val cols=5; val startX=41f; val gap=83f
            val rows=2; val startY=660f
            for(i in apps.indices) {
                val row=i/cols; val col=i%cols
                val x=startX+col*gap; val y=startY+row*88f
                val r=RectF(x-25f,y-25f,x+25f,y+25f)
                rr(c,r,8f,if(decayed) color("#272827") else color("#202832"),if(decayed) color("#6C655C") else color("#AEB8BF"))
                text(c,apps[i].glyph,x-8f,y+8f,25f,if(decayed) color("#77736A") else color("#C0C7CC"))
                if(decayed) {
                    line(c,x-18f,y-16f,x+17f,y+18f,color("#6D6258"),1.2f)
                    line(c,x+17f,y-18f,x-15f,y+13f,color("#514D47"),0.8f)
                }
                val label=apps[i].label
                p.textSize=10f; p.typeface=Typeface.create("serif",Typeface.NORMAL)
                val tw=p.measureText(label)
                text(c,label,x-tw/2,y+43f,10f,ivoryColor())
                iconBounds.add(Pair(r,apps[i]))
            }
        }

        private fun drawFooter(c: Canvas) {
            line(c,24f,847f,396f,847f,color("#7D8991"),1f)
            text(c,if(decayed) "GLISSEZ POUR RESTAURER" else "GLISSEZ POUR RÉVÉLER",112f,830f,11f,ivoryColor(),true)
        }

        private fun drawWatchFace(c: Canvas) {
            p.color=0x99070A0D.toInt(); c.drawRect(0f,0f,420f,850f,p)
            rr(c,RectF(75f,270f,345f,575f),130f,color("#171E25"),color("#AEB8BF"))
            p.color=color("#AEB8BF"); p.style=Paint.Style.STROKE; p.strokeWidth=5f
            c.drawCircle(210f,410f,105f,p); p.style=Paint.Style.FILL
            p.color=color("#303A44"); c.drawCircle(210f,410f,96f,p)
            for(i in 0..11) {
                val a=Math.toRadians((i*30-90).toDouble())
                val x=210+cos(a)*79; val y=410+sin(a)*79
                text(c, (if(i==0) "XII" else if(i==3) "III" else if(i==6) "VI" else if(i==9) "IX" else "·"),
                    x.toFloat()-9,y.toFloat()+5,13f,color("#D0D3D2"))
            }
            val cal=java.util.Calendar.getInstance()
            val min=cal.get(java.util.Calendar.MINUTE); val hour=cal.get(java.util.Calendar.HOUR)
            val ha=Math.toRadians((hour*30+min*.5-90).toDouble())
            val ma=Math.toRadians((min*6-90).toDouble())
            line(c,210f,410f,(210+cos(ha)*48).toFloat(),(410+sin(ha)*48).toFloat(),color("#D3D7D8"),4f)
            line(c,210f,410f,(210+cos(ma)*72).toFloat(),(410+sin(ma)*72).toFloat(),color("#B9C2C9"),2f)
            p.color=color("#AEB8BF"); c.drawCircle(210f,410f,5f,p)
            text(c,SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date()),165f,545f,23f,color("#D1D4D4"))
            text(c,"TOUCHER POUR FERMER",140f,600f,10f,color("#D1D4D4"))
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { downX=e.x/scale; downY=e.y/scale; return true }
                MotionEvent.ACTION_UP -> {
                    val dx=e.x/scale-downX
                    if(abs(dx)>55f) { decayed=dx<0; invalidate(); return true }
                    val x=e.x/scale; val y=e.y/scale
                    if(watchOpen) { watchOpen=false; invalidate(); return true }
                    if(watchBounds.contains(x,y)) { watchOpen=true; invalidate(); return true }
                    if(taskBounds.contains(x,y)) {
                        val index=((y-472f)/29f).toInt()
                        if(index in tasks.indices) { if(checked.contains(index)) checked.remove(index) else checked.add(index) }
                        invalidate(); return true
                    }
                    iconBounds.firstOrNull { it.first.contains(x,y) }?.let { launchApp(it.second.pkg); return true }
                    return true
                }
            }
            return true
        }

        private fun launchApp(pkg: String) {
            try {
                val intent=packageManager.getLaunchIntentForPackage(pkg)
                if(intent!=null) startActivity(intent)
                else android.widget.Toast.makeText(this@MainActivity,"Application non installée",android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                android.widget.Toast.makeText(this@MainActivity,"Impossible d'ouvrir cette application",android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    data class AppItem(val label:String,val pkg:String,val glyph:String)
}
