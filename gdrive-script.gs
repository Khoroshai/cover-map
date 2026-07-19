const FILE_NAME = 'cover-map-highlights.json';

function doGet(e) {
  var callback = e && e.parameter && e.parameter.callback;
  var saveData = e && e.parameter && e.parameter.save;
  var redirect = e && e.parameter && e.parameter.redirect;

  try {
    if (saveData) {
      var parsed = JSON.parse(decodeURIComponent(saveData));
      if (!parsed.highlights) parsed.highlights = [];
      if (parsed.globalOpacity == null) parsed.globalOpacity = 0.5;
      var file = getOrCreateFile();
      file.setContent(JSON.stringify(parsed));
      if (callback) {
        return ContentService.createTextOutput(callback + '({ok:true})')
          .setMimeType(ContentService.MimeType.JAVASCRIPT);
      }
      return ContentService.createTextOutput(JSON.stringify({ ok: true }))
        .setMimeType(ContentService.MimeType.JSON);
    }

  if (redirect) {
      var file = getOrCreateFile();
      var data = file.getBlob().getDataAsString() || '{}';
      
      var token = ScriptApp.getOAuthToken(); 
      var fileId = file.getId();
      
      var sep = redirect.indexOf('?') >= 0 ? '&' : '?';
      var target = redirect + sep + 'data=' + encodeURIComponent(data) + 
                   '&token=' + encodeURIComponent(token) + 
                   '&fileId=' + encodeURIComponent(fileId);
                   
      return HtmlService.createHtmlOutput(
        '<html><head><meta http-equiv="refresh" content="0;url=' + target + '"></head>' +
        '<body>Redirecting... <a href="' + target + '">click here</a></body></html>'
      ).setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
    }

    var file = getOrCreateFile();
    var data = file.getBlob().getDataAsString() || '{}';
    var parsed;
    try { parsed = JSON.parse(data); } catch(err) { parsed = {}; }

    if (callback) {
      return ContentService.createTextOutput(callback + '(' + JSON.stringify(parsed) + ')')
        .setMimeType(ContentService.MimeType.JAVASCRIPT);
    }

    return ContentService.createTextOutput(JSON.stringify(parsed))
      .setMimeType(ContentService.MimeType.JSON);
  } catch(err) {
    var errResp = JSON.stringify({ error: err.message || String(err) });
    if (callback) {
      return ContentService.createTextOutput(callback + '(' + errResp + ')')
        .setMimeType(ContentService.MimeType.JAVASCRIPT);
    }
    if (redirect) {
      var sep = redirect.indexOf('?') >= 0 ? '&' : '?';
      var target = redirect + sep + 'error=' + encodeURIComponent(err.message || String(err));
      return HtmlService.createHtmlOutput(
        '<html><head><meta http-equiv="refresh" content="0;url=' + target + '"></head>' +
        '<body>Auth failed: ' + (err.message || String(err)) + '<br>' +
        '<a href="' + target + '">Return to app</a></body></html>'
      ).setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
    }
    return ContentService.createTextOutput(errResp)
      .setMimeType(ContentService.MimeType.JSON);
  }
}

function doPost(e) {
  try {
    var raw = e.postData.contents;
    var newData;
    try {
      newData = JSON.parse(raw);
    } catch(err) {
      var params = {};
      raw.split('&').forEach(function(pair) {
        var parts = pair.split('=');
        params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1] || '');
      });
      newData = JSON.parse(params.contents || '{}');
    }
    if (!newData.highlights) newData.highlights = [];
    if (newData.globalOpacity == null) newData.globalOpacity = 0.5;
    var file = getOrCreateFile();
    file.setContent(JSON.stringify(newData));
    return ContentService.createTextOutput(JSON.stringify({ ok: true }))
      .setMimeType(ContentService.MimeType.JSON);
  } catch(err) {
    return ContentService.createTextOutput(JSON.stringify({ ok: false, error: err.message || String(err) }))
      .setMimeType(ContentService.MimeType.JSON);
  }
}

function getOrCreateFile() {
  var files = DriveApp.getFilesByName(FILE_NAME);
  if (files.hasNext()) {
    return files.next();
  }
  return DriveApp.createFile(FILE_NAME, '{}', MimeType.PLAIN_TEXT);
}
