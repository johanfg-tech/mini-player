package com.johan.miniplayer;

/** JavaScript que se inyecta en YouTube. */
final class Scripts {
    private Scripts() {}

    /**
     * Se ejecuta antes que el código de YouTube:
     * - quita los anuncios que vienen dentro de la respuesta del reproductor
     * - hace creer a la página que siempre está visible (para que siga sonando
     *   con la pantalla apagada o en otra app)
     */
    static final String DOC_START =
            "(function(){\n" +
            "  var keys=['adPlacements','adSlots','playerAds','adBreakHeartbeatParams'];\n" +
            "  function prune(o){try{if(o&&typeof o==='object'){keys.forEach(function(k){if(k in o)delete o[k];});\n" +
            "    if(o.playerResponse)prune(o.playerResponse);\n" +
            "    if(Array.isArray(o))o.forEach(function(x){if(x&&x.playerResponse)prune(x.playerResponse);});}}catch(e){}return o;}\n" +
            "  var jp=JSON.parse;JSON.parse=function(){return prune(jp.apply(this,arguments));};\n" +
            "  try{var rj=Response.prototype.json;Response.prototype.json=function(){return rj.apply(this,arguments).then(prune);};}catch(e){}\n" +
            "  try{var ipr;Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,get:function(){return ipr;},set:function(v){ipr=prune(v);}});}catch(e){}\n" +
            "  try{Object.defineProperty(document,'hidden',{configurable:true,get:function(){return false;}});\n" +
            "      Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){return 'visible';}});}catch(e){}\n" +
            "  document.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation();},true);\n" +
            "  window.addEventListener('blur',function(e){e.stopImmediatePropagation();},true);\n" +
            "})();";

    /** Lee el estado del video, salta anuncios y aplica repetir. */
    static String poll(boolean repeat, boolean wantPlay) {
        return "(function(){" +
                "var v=document.querySelector('video');" +
                "var p=document.querySelector('#movie_player,.html5-video-player');" +
                "var ad=!!(p&&p.classList.contains('ad-showing'));" +
                "if(v){v.loop=" + repeat + ";" +
                "if(ad){window.__mpAd=1;v.muted=true;if(isFinite(v.duration)&&v.duration>0)v.currentTime=v.duration;" +
                "var b=document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern');if(b)b.click();}" +
                "else if(window.__mpAd){window.__mpAd=0;v.muted=false;}" +
                "if(" + wantPlay + "&&!ad&&v.paused&&v.readyState>=1){var pr=v.play();if(pr&&pr.catch)pr.catch(function(){});}}" +
                "var id=null;try{if(location.pathname==='/watch')id=new URLSearchParams(location.search).get('v');}catch(e){}" +
                "return {id:id,title:document.title.replace(/ - YouTube$/,'')," +
                "cur:v?v.currentTime:0,dur:(v&&isFinite(v.duration))?v.duration:0," +
                "paused:v?v.paused:true,ended:v?v.ended:false,ad:ad};" +
                "})()";
    }
}
