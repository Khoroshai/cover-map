package com.covermap.app;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;

import com.google.android.gms.common.api.ApiException;
import org.json.JSONObject;

import android.util.Base64;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@CapacitorPlugin(name = "GoogleDrive")
public class GoogleDrivePlugin extends Plugin {

    private static final String TAG = "GoogleDrivePlugin";
    private static final String SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file";
    private static final String SCOPE_PHOTOS_READONLY = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly";
    private static final String PREFS_NAME = "gdrive_secure";
    private static final String KEY_TOKEN = "access_token";
    private static final String KEY_FILE_ID = "file_id";
    private static final String KEY_PHOTOS_TOKEN = "photos_access_token";
    private static final String FILE_NAME = "cover-map-highlights.json";
    public static final int REQUEST_AUTHORIZE = 9001;
    private static GoogleDrivePlugin instance;

    private PluginCall pendingAuthorizeCall;

    @Override
    public void load() {
        instance = this;
        super.load();
    }

    public static GoogleDrivePlugin getInstance() {
        return instance;
    }

    private SharedPreferences getSecurePrefs() throws Exception {
        MasterKey masterKey = new MasterKey.Builder(getContext())
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                getContext(),
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    @PluginMethod
    public void authorize(PluginCall call) {
        String storedToken;
        try {
            storedToken = getSecurePrefs().getString(KEY_TOKEN, null);
        } catch (Exception e) {
            call.reject("Failed to read secure storage");
            return;
        }

        if (storedToken != null) {
            ensureFileExists(storedToken);
            JSObject result = new JSObject();
            result.put("connected", true);
            call.resolve(result);
            return;
        }

        Scope driveScope = new Scope(SCOPE_DRIVE_FILE);
        Scope photosScope = new Scope(SCOPE_PHOTOS_READONLY);
        AuthorizationRequest authorizationRequest = AuthorizationRequest.builder()
                .setRequestedScopes(Arrays.asList(driveScope, photosScope))
                .build();

        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity not available");
            return;
        }

        pendingAuthorizeCall = call;

        Identity.getAuthorizationClient(activity)
                .authorize(authorizationRequest)
                .addOnSuccessListener(authorizationResult -> {
                    if (authorizationResult.hasResolution()) {
                        try {
                            IntentSender intentSender = authorizationResult.getPendingIntent().getIntentSender();
                            Intent fillInIntent = new Intent();
                            activity.startIntentSenderForResult(
                                    intentSender,
                                    REQUEST_AUTHORIZE,
                                    fillInIntent,
                                    0, 0, 0
                            );
                        } catch (Exception e) {
                            pendingAuthorizeCall = null;
                            call.reject("Failed to start authorization: " + e.getMessage());
                        }
                    } else {
                        String accessToken = authorizationResult.getAccessToken();
                        if (accessToken != null) {
                            handleTokenReceived(accessToken, call);
                        } else {
                            pendingAuthorizeCall = null;
                            call.reject("No access token received");
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    pendingAuthorizeCall = null;
                    Log.e(TAG, "Authorization failed", e);
                    call.reject("Authorization failed: " + e.getMessage());
                });
    }

    public void handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_AUTHORIZE) return;

        PluginCall call = pendingAuthorizeCall;
        pendingAuthorizeCall = null;

        if (call == null) return;

        if (resultCode == Activity.RESULT_OK && data != null) {
            try {
                AuthorizationResult authorizationResult =
                        Identity.getAuthorizationClient(getContext())
                                .getAuthorizationResultFromIntent(data);

                String accessToken = authorizationResult.getAccessToken();
                if (accessToken != null) {
                    handleTokenReceived(accessToken, call);
                } else {
                    call.reject("No access token received");
                }
            } catch (ApiException e) {
                call.reject("Failed to parse authorization result: " + e.getStatusCode());
            }
        } else {
            call.reject("Authorization was cancelled");
        }
    }

    private void handleTokenReceived(String accessToken, PluginCall call) {
        new Thread(() -> {
            try {
                getSecurePrefs().edit().putString(KEY_TOKEN, accessToken).apply();
                ensureFileExists(accessToken);
                JSObject result = new JSObject();
                result.put("connected", true);
                call.resolve(result);
            } catch (Exception e) {
                call.reject("Failed to store token: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void isConnected(PluginCall call) {
        try {
            String token = getSecurePrefs().getString(KEY_TOKEN, null);
            String fileId = getSecurePrefs().getString(KEY_FILE_ID, null);
            JSObject result = new JSObject();
            result.put("connected", token != null && fileId != null);
            call.resolve(result);
        } catch (Exception e) {
            JSObject result = new JSObject();
            result.put("connected", false);
            call.resolve(result);
        }
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        String token;
        String fileId;
        try {
            token = getSecurePrefs().getString(KEY_TOKEN, null);
            fileId = getSecurePrefs().getString(KEY_FILE_ID, null);
        } catch (Exception e) {
            call.reject("Failed to read secure storage");
            return;
        }
        if (token == null || fileId == null) {
            call.reject("Not connected");
            return;
        }

        new Thread(() -> {
            try {
                URL url = new URL("https://www.googleapis.com/drive/v3/files/" + fileId + "?alt=media");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + token);

                int code = conn.getResponseCode();
                if (code == 401) {
                    getSecurePrefs().edit().clear().apply();
                    call.reject("Token expired");
                    return;
                }
                if (code != 200) {
                    call.reject("Failed to read file: " + code);
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject data = new JSONObject(sb.toString());
                JSObject result = new JSObject();
                result.put("highlights", data.optJSONArray("highlights"));
                result.put("globalOpacity", data.optDouble("globalOpacity", 0.5));
                result.put("photos", data.optJSONArray("photos"));
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "Read file failed", e);
                call.reject("Read failed: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        String token;
        String fileId;
        try {
            token = getSecurePrefs().getString(KEY_TOKEN, null);
            fileId = getSecurePrefs().getString(KEY_FILE_ID, null);
        } catch (Exception e) {
            call.reject("Failed to read secure storage");
            return;
        }
        if (token == null || fileId == null) {
            call.reject("Not connected");
            return;
        }

        String data = call.getString("data");
        if (data == null) {
            call.reject("No data provided");
            return;
        }

        new Thread(() -> {
            try {
                URL url = new URL("https://www.googleapis.com/upload/drive/v3/files/" + fileId + "?uploadType=media");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PATCH");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                os.write(data.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (code == 401) {
                    getSecurePrefs().edit().clear().apply();
                    call.reject("Token expired");
                    return;
                }

                JSObject result = new JSObject();
                result.put("ok", code == 200);
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "Write file failed", e);
                call.reject("Write failed: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void signOut(PluginCall call) {
        try {
            getSecurePrefs().edit().clear().apply();
            JSObject result = new JSObject();
            result.put("connected", false);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to sign out");
        }
    }

    @PluginMethod
    public void createPickerSession(PluginCall call) {
        String token;
        try {
            token = getSecurePrefs().getString(KEY_TOKEN, null);
        } catch (Exception e) {
            call.reject("Failed to read secure storage");
            return;
        }
        if (token == null) {
            call.reject("Not connected");
            return;
        }

        new Thread(() -> {
            try {
                URL url = new URL("https://photospicker.googleapis.com/v1/sessions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                int code = conn.getResponseCode();
                if (code == 401) {
                    getSecurePrefs().edit().clear().apply();
                    call.reject("Token expired");
                    return;
                }
                if (code != 200) {
                    BufferedReader errReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    StringBuilder errSb = new StringBuilder();
                    String line;
                    while ((line = errReader.readLine()) != null) errSb.append(line);
                    errReader.close();
                    Log.e(TAG, "createPickerSession error: " + errSb.toString());
                    call.reject("Failed to create picker session: " + code);
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                JSObject result = new JSObject();
                result.put("id", json.optString("id"));
                result.put("pickerUri", json.optString("pickerUri"));
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "createPickerSession failed", e);
                call.reject("Failed to create picker session: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void getSession(PluginCall call) {
        String token;
        try {
            token = getSecurePrefs().getString(KEY_TOKEN, null);
        } catch (Exception e) {
            call.reject("Failed to read secure storage");
            return;
        }
        if (token == null) {
            call.reject("Not connected");
            return;
        }

        String sessionId = call.getString("sessionId");
        if (sessionId == null) {
            call.reject("No sessionId provided");
            return;
        }

        new Thread(() -> {
            try {
                URL url = new URL("https://photospicker.googleapis.com/v1/sessions/" + sessionId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + token);

                int code = conn.getResponseCode();
                if (code == 401) {
                    getSecurePrefs().edit().clear().apply();
                    call.reject("Token expired");
                    return;
                }
                if (code != 200) {
                    call.reject("Failed to get session: " + code);
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                JSObject result = new JSObject();
                result.put("id", json.optString("id"));
                result.put("mediaItemsSet", json.optBoolean("mediaItemsSet", false));
                result.put("pollingConfig", json.optJSONObject("pollingConfig"));
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "getSession failed", e);
                call.reject("Failed to get session: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void listPickedMediaItems(PluginCall call) {
        String token;
        try {
            token = getSecurePrefs().getString(KEY_TOKEN, null);
        } catch (Exception e) {
            call.reject("Failed to read secure storage");
            return;
        }
        if (token == null) {
            call.reject("Not connected");
            return;
        }

        String sessionId = call.getString("sessionId");
        if (sessionId == null) {
            call.reject("No sessionId provided");
            return;
        }

        String pageToken = call.getString("pageToken", null);

        new Thread(() -> {
            try {
                StringBuilder urlStr = new StringBuilder("https://photospicker.googleapis.com/v1/mediaItems?sessionId=" + sessionId);
                if (pageToken != null && !pageToken.isEmpty()) {
                    urlStr.append("&pageToken=").append(pageToken);
                }
                URL url = new URL(urlStr.toString());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Bearer " + token);

                int code = conn.getResponseCode();
                if (code == 401) {
                    getSecurePrefs().edit().clear().apply();
                    call.reject("Token expired");
                    return;
                }
                if (code != 200) {
                    BufferedReader errReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                    StringBuilder errSb = new StringBuilder();
                    String line;
                    while ((line = errReader.readLine()) != null) errSb.append(line);
                    errReader.close();
                    Log.e(TAG, "listPickedMediaItems error: " + errSb.toString());
                    call.reject("Failed to list picked media items: " + code);
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                JSObject result = new JSObject();
                result.put("mediaItems", json.optJSONArray("mediaItems"));
                result.put("nextPageToken", json.optString("nextPageToken", null));
                call.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "listPickedMediaItems failed", e);
                call.reject("Failed to list picked media items: " + e.getMessage());
            }
        }).start();
    }

    private void ensureFileExists(String token) {
        try {
            String existingFileId = getSecurePrefs().getString(KEY_FILE_ID, null);
            if (existingFileId != null && fileExists(token, existingFileId)) return;

            String fileId = findFileByName(token, FILE_NAME);
            if (fileId != null) {
                getSecurePrefs().edit().putString(KEY_FILE_ID, fileId).apply();
                return;
            }

            fileId = createFile(token, FILE_NAME, "{}");
            if (fileId != null) {
                getSecurePrefs().edit().putString(KEY_FILE_ID, fileId).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "ensureFileExists failed", e);
        }
    }

    private boolean fileExists(String token, String fileId) {
        try {
            URL url = new URL("https://www.googleapis.com/drive/v3/files/" + fileId + "?fields=id");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private String findFileByName(String token, String name) {
        try {
            String query = "name='" + name + "' and trashed=false";
            String encodedQuery = URLEncoder.encode(query, "UTF-8");
            String fields = "files(id)";
            String encodedFields = URLEncoder.encode(fields, "UTF-8");
            URL url = new URL("https://www.googleapis.com/drive/v3/files?q=" + encodedQuery + "&fields=" + encodedFields);
            Log.d(TAG, "Searching for file: " + url.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            int code = conn.getResponseCode();
            Log.d(TAG, "Find file response code: " + code);
            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();
                String response = sb.toString();
                Log.d(TAG, "Find file response: " + response);

                JSONObject json = new JSONObject(response);
                org.json.JSONArray files = json.optJSONArray("files");
                if (files != null && files.length() > 0) {
                    String foundId = files.getJSONObject(0).getString("id");
                    Log.d(TAG, "Found existing file: " + foundId);
                    return foundId;
                }
                Log.d(TAG, "No existing file found");
            } else {
                BufferedReader errReader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuilder errSb = new StringBuilder();
                String line;
                while ((line = errReader.readLine()) != null) errSb.append(line);
                errReader.close();
                Log.e(TAG, "Find file error: " + errSb.toString());
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Find file failed", e);
        }
        return null;
    }

    private String createFile(String token, String name, String content) {
        try {
            String metadata = "{\"name\":\"" + name + "\",\"mimeType\":\"application/json\"}";
            String boundary = "----CoverMapBoundary";
            String CRLF = "\r\n";

            URL url = new URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(("--" + boundary + CRLF).getBytes());
            os.write(("Content-Type: application/json; charset=UTF-8" + CRLF + CRLF).getBytes());
            os.write(metadata.getBytes(StandardCharsets.UTF_8));
            os.write((CRLF + "--" + boundary + CRLF).getBytes());
            os.write(("Content-Type: application/json; charset=UTF-8" + CRLF + CRLF).getBytes());
            os.write(content.getBytes(StandardCharsets.UTF_8));
            os.write((CRLF + "--" + boundary + "--" + CRLF).getBytes());
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(sb.toString());
                return json.getString("id");
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "Create file failed", e);
        }
        return null;
    }

    @PluginMethod
    public void fetchImageAsBase64(PluginCall call) {
        String imageUrl = call.getString("url");
        if (imageUrl == null) {
            call.reject("No url provided");
            return;
        }

        new Thread(() -> {
            try {
                // Try without auth first (pre-authenticated URL)
                ImageFetchResult result = fetchImage(imageUrl, null);
                if (result.code == 200) {
                    resolveImage(call, result);
                    return;
                }
                Log.d(TAG, "fetchImageAsBase64: no-auth returned " + result.code + ", trying with token");

                // Fallback with Bearer token
                String token = getSecurePrefs().getString(KEY_TOKEN, null);
                if (token != null) {
                    result = fetchImage(imageUrl, token);
                    if (result.code == 200) {
                        resolveImage(call, result);
                        return;
                    }
                    Log.d(TAG, "fetchImageAsBase64: with-token returned " + result.code);
                }

                call.reject("Image fetch failed: HTTP " + result.code);
            } catch (Exception e) {
                Log.e(TAG, "fetchImageAsBase64 failed", e);
                call.reject("Image fetch failed: " + e.getMessage());
            }
        }).start();
    }

    private static class ImageFetchResult {
        int code;
        byte[] bytes;
        String mimeType;
    }

    private ImageFetchResult fetchImage(String imageUrl, String token) throws Exception {
        URL url = new URL(imageUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        conn.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
        conn.setRequestProperty("Referer", "https://photospicker.googleapis.com/");
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }

        ImageFetchResult result = new ImageFetchResult();
        result.code = conn.getResponseCode();
        if (result.code == 200) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = conn.getInputStream().read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            result.bytes = baos.toByteArray();
            result.mimeType = conn.getContentType();
            if (result.mimeType == null || !result.mimeType.startsWith("image/")) {
                result.mimeType = "image/jpeg";
            }
        }
        conn.disconnect();
        return result;
    }

    private void resolveImage(PluginCall call, ImageFetchResult result) {
        String base64 = Base64.encodeToString(result.bytes, Base64.NO_WRAP);
        JSObject res = new JSObject();
        res.put("base64", base64);
        res.put("mimeType", result.mimeType);
        call.resolve(res);
    }
}
