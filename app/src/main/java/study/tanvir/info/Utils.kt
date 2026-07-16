package study.tanvir.info

import android.webkit.WebView

fun WebView.fetchBlob(blobUrl: String, mimeType: String?, cd: String?) {
    val safeMime = mimeType?.replace("'", "\\'") ?: ""
    val safeCD = cd?.replace("'", "\\'") ?: ""

    val js = """
        (async function() {
            try {
                const response = await fetch('$blobUrl');
                const blob = await response.blob();
                const reader = new FileReader();
                reader.onloadend = function() {
                    BlobDownloader.download(reader.result, '$safeMime', '$safeCD');
                };
                reader.readAsDataURL(blob);
                return;
            } catch(e) {}

            if (window._lastBlob) {
                const reader2 = new FileReader();
                reader2.onloadend = function() {
                    BlobDownloader.download(reader2.result, '$safeMime', '$safeCD');
                };
                reader2.readAsDataURL(window._lastBlob);
                return;
            }

            BlobDownloader.error('Blob fetch failed');
        })();
    """.trimIndent()

    evaluateJavascript(js, null)
}
