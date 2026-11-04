package store.carlos60fps.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final String HOME = "https://carlos60fps.store/";
    private static final String HOST = "carlos60fps.store";
    private static final int REQ_FILE = 1;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on while a video is being patched
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.addJavascriptInterface(new Bridge(), "AndroidDL");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (scheme.equals("blob") || scheme.equals("data")
                        || scheme.equals("about") || scheme.equals("javascript")) {
                    return false;
                }
                String host = u.getHost();
                if ((scheme.equals("http") || scheme.equals("https")) && host != null
                        && (host.equals(HOST) || host.endsWith("." + HOST))) {
                    return false; // stay inside the app
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u)); // Telegram, other sites...
                } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url.startsWith("blob:")) {
                saveBlob(url, mimeType);
            } else {
                try {
                    DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
                    r.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    if (mimeType != null && !mimeType.isEmpty()) r.setMimeType(mimeType);
                    r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                            URLUtil.guessFileName(url, contentDisposition, mimeType));
                    ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(r);
                    toast("Download started");
                } catch (Exception e) {
                    toast("Download failed: " + e.getMessage());
                }
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }
    }

    // Blob downloads (patched video generated in the browser) are streamed
    // to the Downloads folder in 3 MB chunks so big files don't run out of memory.
    private void saveBlob(String blobUrl, String mimeType) {
        String type = (mimeType == null || mimeType.isEmpty()) ? "video/mp4" : mimeType;
        String js = "(async()=>{try{"
                + "const b=await (await fetch('" + blobUrl + "')).blob();"
                + "AndroidDL.begin('" + type + "');"
                + "const C=3*1024*1024;"
                + "for(let o=0;o<b.size;o+=C){"
                + "const d=await new Promise((res,rej)=>{const r=new FileReader();"
                + "r.onload=()=>res(r.result.split(',')[1]);r.onerror=rej;"
                + "r.readAsDataURL(b.slice(o,o+C));});"
                + "AndroidDL.chunk(d);}"
                + "AndroidDL.end();"
                + "}catch(e){AndroidDL.fail(String(e));}})();";
        web.evaluateJavascript(js, null);
    }

    private class Bridge {
        private OutputStream out;
        private String name;

        @JavascriptInterface
        public void begin(String mime) {
            try {
                String ext = mime.startsWith("video/") ? ".mp4" : ".bin";
                name = "CARLOS4QUALITY_" + System.currentTimeMillis() + ext;
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                out = getContentResolver().openOutputStream(uri);
                toast("Saving video...");
            } catch (Exception e) {
                out = null;
                toast("Save failed: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void chunk(String b64) {
            try {
                if (out != null) out.write(Base64.decode(b64, Base64.DEFAULT));
            } catch (Exception e) {
                fail(e.toString());
            }
        }

        @JavascriptInterface
        public void end() {
            try {
                if (out != null) {
                    out.close();
                    toast("Saved to Downloads: " + name);
                }
            } catch (Exception e) {
                toast("Save failed: " + e.getMessage());
            }
            out = null;
        }

        @JavascriptInterface
        public void fail(String msg) {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            out = null;
            toast("Save failed: " + msg);
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && filePathCallback != null) {
            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
