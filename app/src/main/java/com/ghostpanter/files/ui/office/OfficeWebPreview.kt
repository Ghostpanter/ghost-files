package com.ghostpanter.files.ui.office

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Offline WebView preview for docx (mammoth.js) and xlsx (SheetJS from official CDN release,
 * Apache-2.0). Scripts are inlined from assets — no network, no file access from JS.
 */
@Composable
fun OfficeWebPreview(
    file: File,
    kind: String,
    onOpenExternal: () -> Unit,
) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var readyHtml by remember { mutableStateOf<String?>(null) }
    var sheetNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var sheetIndex by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(file, kind) {
        error = null
        readyHtml = null
        sheetNames = emptyList()
        sheetIndex = 0
        runCatching {
            withContext(Dispatchers.IO) {
                val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                when (kind) {
                    "docx" -> DocxHtml.build(ctx, b64)
                    "xlsx" -> XlsxHtml.build(ctx, b64)
                    else -> error("unsupported")
                }
            }
        }.onSuccess { readyHtml = it }
            .onFailure { error = it.message ?: t("无法预览", "Cannot preview") }
    }

    when {
        error != null -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Button(onClick = onOpenExternal, modifier = Modifier.padding(top = 12.dp)) {
                Text(t("用其他应用打开", "Open with another app"))
            }
        }
        readyHtml == null -> Box(Modifier.fillMaxSize()) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        else -> Column(Modifier.fillMaxSize()) {
            if (kind == "xlsx" && sheetNames.isNotEmpty()) {
                ScrollableTabRow(selectedTabIndex = sheetIndex.coerceAtMost(sheetNames.lastIndex)) {
                    sheetNames.forEachIndexed { i, name ->
                        Tab(
                            selected = sheetIndex == i,
                            onClick = {
                                sheetIndex = i
                                web?.evaluateJavascript("window.__gfShowSheet && window.__gfShowSheet($i)", null)
                            },
                            text = { Text(name) },
                        )
                    }
                }
            }
            SecureOfficeWebView(
                html = readyHtml!!,
                onSheets = { sheetNames = it; sheetIndex = 0 },
                onError = { error = it },
                onWebView = { web = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(8.dp)) {
                Button(onClick = onOpenExternal) {
                    Text(t("用其他应用打开", "Open with another app"))
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SecureOfficeWebView(
    html: String,
    onSheets: (List<String>) -> Unit,
    onError: (String) -> Unit,
    onWebView: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.blockNetworkImage = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.domStorageEnabled = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun sheets(json: String) {
                        val names = runCatching {
                            val arr = JSONArray(json)
                            (0 until arr.length()).map { arr.getString(it) }
                        }.getOrDefault(emptyList())
                        post { onSheets(names) }
                    }
                    @JavascriptInterface
                    fun fail(msg: String) {
                        post { onError(msg) }
                    }
                }, "GhostOffice")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                }
                onWebView(this)
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

private fun Context.readAsset(path: String): String =
    assets.open(path).bufferedReader().use { it.readText() }

private object DocxHtml {
    fun build(ctx: Context, base64: String): String {
        val mammoth = ctx.readAsset("office/mammoth.browser.min.js")
        val converting = t("正在转换…", "Converting…")
        return """
<!DOCTYPE html><html><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<style>
body{font-family:sans-serif;padding:12px;margin:0;background:#fff;color:#111;}
img{max-width:100%;} table{border-collapse:collapse;}
td,th{border:1px solid #ccc;padding:4px;}
</style>
<script>$mammoth</script>
</head><body>
<div id="out">$converting</div>
<script>
(function(){
  try {
    var b64 = "$base64";
    var bin = atob(b64);
    var bytes = new Uint8Array(bin.length);
    for (var i=0;i<bin.length;i++) bytes[i]=bin.charCodeAt(i);
    mammoth.convertToHtml({arrayBuffer: bytes.buffer}).then(function(r){
      document.getElementById('out').innerHTML = r.value || '<i>empty</i>';
    }).catch(function(e){ GhostOffice.fail(String(e)); });
  } catch(e) { GhostOffice.fail(String(e)); }
})();
</script>
</body></html>
""".trimIndent()
    }
}

private object XlsxHtml {
    fun build(ctx: Context, base64: String): String {
        val xlsx = ctx.readAsset("office/xlsx.full.min.js")
        val parsing = t("正在解析表格…", "Parsing spreadsheet…")
        return """
<!DOCTYPE html><html><head><meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<style>
body{font-family:sans-serif;margin:0;padding:8px;background:#fff;color:#111;}
table{border-collapse:collapse;font-size:12px;}
td,th{border:1px solid #bbb;padding:3px 6px;white-space:nowrap;}
.sheet{display:none;overflow:auto;} .sheet.active{display:block;}
</style>
<script>$xlsx</script>
</head><body>
<div id="root">$parsing</div>
<script>
(function(){
  try {
    var b64 = "$base64";
    var wb = XLSX.read(b64, {type:'base64', cellDates:true});
    var names = wb.SheetNames || [];
    GhostOffice.sheets(JSON.stringify(names));
    var root = document.getElementById('root');
    root.innerHTML = '';
    names.forEach(function(name, idx){
      var div = document.createElement('div');
      div.className = 'sheet' + (idx===0 ? ' active' : '');
      div.id = 'sheet-'+idx;
      div.innerHTML = XLSX.utils.sheet_to_html(wb.Sheets[name], {editable:false});
      root.appendChild(div);
    });
    window.__gfShowSheet = function(i){
      var nodes = document.querySelectorAll('.sheet');
      for (var n=0;n<nodes.length;n++) nodes[n].classList.toggle('active', n===i);
    };
  } catch(e) { GhostOffice.fail(String(e)); }
})();
</script>
</body></html>
""".trimIndent()
    }
}
