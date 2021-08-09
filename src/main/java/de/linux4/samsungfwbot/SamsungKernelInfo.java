/*
  Copyright (C) 2021  Tim Zimmermann <tim@linux4.de>

  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as
  published by the Free Software Foundation, either version 3 of the
  License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.linux4.samsungfwbot;

import org.apache.commons.io.FileUtils;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SamsungKernelInfo {

    private static final String OSS_BASE_URL = "https://opensource.samsung.com";
    private static final String OSS_SEARCH_URL = OSS_BASE_URL + "/uploadSearch?searchValue=";

    private final String model;
    private final String pda;
    private final String uploadId;

    public SamsungKernelInfo(String model, String pda, String uploadId) {
        this.model = model;
        this.pda = pda;
        this.uploadId = uploadId;
    }

    public String getModel() {
        return model;
    }

    public String getPDA() {
        return pda;
    }

    public String getUploadID() {
        return uploadId;
    }

    private static int getPDAVersion(String pda) {
        int version = 0;

        for (int i = pda.length() - 4; i < pda.length(); i++) {
            version += pda.charAt(i);
        }

        return version;
    }

    public boolean isNewerThan(String oldPDA) {
        return oldPDA.length() < 4 || (pda.length() >= 4 && getPDAVersion(oldPDA) < getPDAVersion(pda));
    }

    @Override
    public String toString() {
        return "SamsungKernel(" + model + ", " + pda + ", " + uploadId + ")";
    }

    public static SamsungKernelInfo fetchLatest(String model) throws IOException {
        try {
            Document doc = Jsoup.connect(OSS_SEARCH_URL + model).timeout(10*60*1000).get();

            Elements tableData = doc.getElementsByTag("td");

            if (tableData.size() > 4) {
                String[] fwVersions = tableData.get(2).html().strip().split("<br>");
                String fwVersion = fwVersions.length > 0 ? fwVersions[fwVersions.length - 1].strip() : "";

                String uploadId = "";
                Element downloadTd = tableData.get(4);

                String[] broken = downloadTd.html().split("'");

                if (broken.length > 1)
                    uploadId = broken[1].strip();

                return new SamsungKernelInfo(model, fwVersion, uploadId);
            }
        } catch (HttpStatusException ignored) {

        }

        return null;
    }

    public File download(File folder) throws IOException {
        File dst = new File(folder, model + "-" + pda + ".zip");

        Connection.Response res = Jsoup.connect(OSS_BASE_URL + "/downSrcMPop?uploadId=" + uploadId).timeout(10*60*1000).execute();
        Document doc = res.parse();
        Elements _csrfElem = doc.getElementsByAttributeValue("name", "_csrf");
        Elements checkboxes = doc.getElementsByAttributeValue("type", "checkbox");

        if (_csrfElem.size() > 0 && checkboxes.size() > 1) {
            String _csrf = _csrfElem.get(0).val();
            String attachIds = checkboxes.get(1).id();

            Element tokenElem = doc.getElementById("token");

            if (tokenElem != null) {
                String token = tokenElem.val();
                String query = "_csrf=" + _csrf + "&uploadId=" + uploadId + "&attachIds=" + attachIds
                        + "&downloadPurpose=ETC&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
                byte[] queryBin = query.getBytes(StandardCharsets.UTF_8);
                StringBuilder cookie = new StringBuilder();

                for (String cookieKey : res.cookies().keySet()) {
                    if (cookie.length() > 0)
                        cookie.append("; ");

                    cookie.append(cookieKey).append("=").append(res.cookies().get(cookieKey));
                }

                cookie.append("; __COM_SPEED=H");
                cookie.append("; device_type=pc");
                cookie.append("; fileDownload=true");

                HttpURLConnection conn = (HttpURLConnection) new URL(OSS_BASE_URL + "/downSrcCode").openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                conn.setRequestProperty("Content-Length", "" + queryBin.length);
                conn.setRequestProperty("Cookie", cookie.toString());
                conn.setRequestProperty("Origin", OSS_BASE_URL);
                conn.setRequestProperty("Referer", OSS_SEARCH_URL + model);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:90.0) Gecko/20100101 Firefox/90.0");
                conn.connect();

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(queryBin);
                }

                if (conn.getResponseCode() == HttpURLConnection.HTTP_OK &&
                        "binary".equals(conn.getHeaderField("Content-Transfer-Encoding"))) {
                    FileUtils.copyInputStreamToFile(conn.getInputStream(), dst);

                    return dst;
                }
            }
        }

        return null;
    }

}