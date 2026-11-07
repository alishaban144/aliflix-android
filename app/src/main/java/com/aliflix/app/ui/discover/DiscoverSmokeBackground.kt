package com.aliflix.app.ui.discover

import android.os.Build
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.testTag
import com.aliflix.app.ui.common.rememberMobileAnimationClock
import com.aliflix.app.ui.common.rememberMobileMotionEnabled
import com.aliflix.app.ui.common.rememberMobileResumed

/** LRan's MIT Smoky Button field, ported from GLSL to AGSL. The equations,
 * seven-octave noise and speed=1 are retained; x is mirrored for the orb. */
@Composable internal fun Modifier.discoverSmokeBackground():Modifier {
    val active=rememberMobileMotionEnabled()&&rememberMobileResumed()
    val seconds=rememberMobileAnimationClock(active)
    val field=remember { if(Build.VERSION.SDK_INT>=33)runCatching{DiscoverSmokeField()}.getOrNull()else null }
    return testTag(if(field!=null)"discover-smoke-live"else "discover-smoke-fallback").drawWithCache {
        val fallback=Brush.horizontalGradient(listOf(Color(0xFF55438B),Color(0xFF29213F),Color.Transparent))
        val shade=Brush.horizontalGradient(listOf(Color.Black.copy(alpha=.28f),Color.Transparent))
        onDrawBehind {
            if(Build.VERSION.SDK_INT>=33&&field!=null){
                field.shader.setFloatUniform("uResolution",size.width,size.height)
                field.shader.setFloatUniform("uTime",seconds.value)
                drawRect(field.brush)
            }else drawRect(fallback)
            drawRect(shade)
        }
    }
}

@RequiresApi(33) private class DiscoverSmokeField {
    val shader=RuntimeShader(SMOKE)
    val brush=ShaderBrush(shader)
}

// Source: https://21st.dev/@lran1028/components/smoky-button (MIT).
private const val SMOKE="""
uniform float2 uResolution;
uniform float uTime;
float random(float2 p){return fract(sin(dot(p,float2(12.9898,78.233)))*43758.5453);}
float noise(float2 p){
 float2 cell=floor(p), offset=fract(p), blend=offset*offset*(3.0-2.0*offset);
 float a=random(cell),b=random(cell+float2(1,0)),c=random(cell+float2(0,1)),d=random(cell+float2(1,1));
 return mix(a,b,blend.x)+(c-a)*blend.y*(1.0-blend.x)+(d-b)*blend.x*blend.y;
}
float fbm(float2 p){
 float value=0.0,amplitude=0.5;
 for(int i=0;i<7;i++){
  value+=amplitude*noise(p);
  p=float2(0.8776*p.x-0.4794*p.y,0.4794*p.x+0.8776*p.y)*2.04+float2(13.7,9.2);
  amplitude*=0.5;
 }
 return value;
}
half4 main(float2 frag){
 // Match WebGL's bottom-left origin and mirror the complete field horizontally.
 float2 pixel=float2(uResolution.x-frag.x,uResolution.y-frag.y);
 float2 uv=pixel/uResolution;
 float2 position=(pixel-0.5*uResolution)/uResolution.y;
 float time=uTime*0.58;
 float3 primary=float3(0.447,0.337,0.910),secondary=float3(0.765,0.569,0.827),shadow=float3(0.035,0.027,0.055);
 float2 silk=position;
 silk.y+=0.20*sin(position.x*1.55-time*1.8)+0.11*sin(position.x*3.2+time*1.35);
 silk.x+=0.06*sin(position.y*4.2-time*1.1);
 float2 warp=float2(fbm(silk*1.15+float2(time*0.28,-time*0.17)),fbm(silk*1.05+float2(4.8,1.3)-float2(time*0.18,time*0.22)));
 float2 current=silk+(warp-0.5)*0.82;
 float body=fbm(current*1.35+float2(-time*0.24,time*0.14));
 float detail=fbm(current*2.7+float2(time*0.31,-time*0.27));
 float p=fbm(current*1.20+float2(-time*0.38,time*0.18));
 float s=fbm(current*1.10+float2(time*0.26,-time*0.32)+float2(7.2,3.6));
 float h=fbm(current*1.45+float2(-time*0.18,time*0.42)+float2(2.4,8.1));
 float bottom=fbm(float2(current.x*0.92-time*0.34,current.y*0.76+time*0.16)+float2(6.4,2.3));
 float shared=current.y*6.8+current.x*1.35+(warp.x-warp.y)*5.2;
 float cross=current.y*5.4-current.x*2.1+(warp.x+warp.y)*3.5;
 float pr=smoothstep(0.08,0.92,0.5+0.5*sin(shared-time*2.75));
 float sr=smoothstep(0.08,0.92,0.5+0.5*sin(cross+time*2.35+2.1));
 float hr=smoothstep(0.12,0.88,0.5+0.5*sin(shared*0.78-cross*0.22-time*3.25+4.2));
 float pw=smoothstep(0.27,0.76,p+0.24*body)*(0.20+1.08*pr);
 float sw=smoothstep(0.26,0.74,s+0.22*body)*(0.22+1.04*sr+0.12*detail);
 float hw=0.16+smoothstep(0.32,0.80,h+0.14*body)*(0.22+0.86*hr);
 float3 color=(primary*pw+secondary*sw+shadow*hw)/max(pw+sw+hw,0.001);
 float depth=smoothstep(0.15,0.88,body*0.78+detail*0.30);
 color*=0.65+depth*0.62;
 float vein=smoothstep(0.46,0.80,h+0.10*body+0.16*hr);
 color=mix(color,shadow,vein*(0.12+0.42*hr));
 float sheen=pow(0.5+0.5*sin(shared*1.22-time*3.2+detail*2.0),6.0);
 color+=mix(secondary,primary,pr)*sheen*(0.10+0.16*depth);
 float ridge=0.26+0.24*bottom+0.08*sin(current.x*2.35-time*2.6+warp.x*3.0);
 float envelope=1.0-smoothstep(ridge,ridge+0.24,uv.y);
 float cloud=smoothstep(0.18,0.72,bottom+0.22*detail);
 float mask=envelope*(0.48+0.52*cloud);
 float flow=0.5+0.5*sin(current.x*2.1-time*2.4+warp.y*4.0);
 float3 bottomColor=mix(primary,secondary,smoothstep(0.12,0.88,flow));
 bottomColor=mix(bottomColor,shadow,smoothstep(0.48,0.78,h+0.18*bottom)*0.72);
 bottomColor+=mix(secondary,primary,flow)*pow(0.5+0.5*sin(flow*5.0+time*2.2),5.0)*0.12;
 color=mix(color,bottomColor,mask*0.72);
 float feather=uv.x+0.12*(body-0.5)+0.08*(bottom-0.5)+0.04*sin(current.y*3.0-time*1.4);
 float alpha=smoothstep(0.24,0.72,feather);
 return half4(clamp(color,0.0,1.0)*alpha,alpha);
}
"""
