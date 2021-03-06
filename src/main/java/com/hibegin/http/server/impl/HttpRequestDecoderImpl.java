package com.hibegin.http.server.impl;

import com.hibegin.common.util.BytesUtil;
import com.hibegin.common.util.IOUtil;
import com.hibegin.common.util.LoggerUtil;
import com.hibegin.http.server.api.HttpRequest;
import com.hibegin.http.server.api.HttpRequestDeCoder;
import com.hibegin.http.server.config.ConfigKit;
import com.hibegin.http.server.config.RequestConfig;
import com.hibegin.http.server.execption.ContentLengthTooLargeException;
import com.hibegin.http.server.execption.UnSupportMethodException;
import com.hibegin.http.server.handler.ReadWriteSelectorHandler;
import com.hibegin.http.server.util.PathUtil;

import java.io.*;
import java.net.SocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HttpRequestDecoderImpl implements HttpRequestDeCoder {

    protected static final String CRLF = "\r\n";
    protected static final String SPLIT = CRLF + CRLF;
    private static final Logger LOGGER = LoggerUtil.getLogger(HttpRequestDecoderImpl.class);
    private SimpleHttpRequest request;


    public HttpRequestDecoderImpl(SocketAddress socketAddress, RequestConfig requestConfig, ServerContext serverContext, ReadWriteSelectorHandler handler) {
        this.request = new SimpleHttpRequest(System.currentTimeMillis(), handler, serverContext);
        this.request.requestConfig = requestConfig;
        this.request.ipAddr = socketAddress;
        if (requestConfig.isSsl()) {
            request.scheme = "https";
        }
    }

    private static String randomFile() {
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMddHHmmss");
        return df.format(new Date()) + "_" + new Random().nextInt(1000);
    }

    @Override
    public boolean doDecode(byte[] data) throws Exception {
        boolean flag = false;
        if (request.dataBuffer == null) {
            request.headerSb.append(new String(data));
            if (request.headerSb.toString().contains(SPLIT)) {
                String fullData = request.headerSb.toString();
                String httpHeader = fullData.substring(0, fullData.indexOf(SPLIT));
                String headerArr[] = httpHeader.split(CRLF);
                String pHeader = headerArr[0];
                if (!"".equals(pHeader.split(" ")[0])) {
                    // parse HttpHeader
                    parseHttpProtocolHeader(headerArr, pHeader);
                    flag = parseHttpMethod(data, httpHeader);
                }
            } else {
                checkHttpMethod();
            }
        } else {
            request.dataBuffer.put(data);
            flag = !request.dataBuffer.hasRemaining();
            if (flag) {
                dealPostData();
            }
        }
        return flag;
    }

    private void checkHttpMethod() throws UnSupportMethodException {
        boolean check = false;
        for (HttpMethod httpMethod : HttpMethod.values()) {
            if (request.headerSb.length() > httpMethod.name().length()) {
                String tHttpMethod = request.headerSb.substring(0, httpMethod.name().length());
                if (tHttpMethod.equals(httpMethod.name())) {
                    check = true;
                }
            }
        }
        if (!check) {
            throw new UnSupportMethodException("");
        }
    }

    private boolean parseHttpMethod(byte[] data, String httpHeader) {
        boolean flag = false;

        if (request.method == HttpMethod.GET || request.method == HttpMethod.CONNECT) {
            wrapperParamStrToMap(request.queryStr);
            flag = true;
        } else if (request.method == HttpMethod.POST || request.method == HttpMethod.DELETE || request.method == HttpMethod.PUT) {
            // 存在2种情况
            // 1,POST 提交的数据一次性读取完成。
            // 2,POST 提交的数据一次性读取不完。
            wrapperParamStrToMap(request.queryStr);
            if (request.header.get("Content-Length") != null) {
                Integer dateLength = Integer.parseInt(request.header.get("Content-Length"));
                if (dateLength > ConfigKit.getMaxUploadSize()) {
                    throw new ContentLengthTooLargeException("Content-Length outSide the max uploadSize "
                            + ConfigKit.getMaxUploadSize());
                }
                request.dataBuffer = ByteBuffer.allocate(dateLength);
                int headerLength = httpHeader.getBytes().length + SPLIT.getBytes().length;
                byte[] remain = BytesUtil.subBytes(data, headerLength, data.length - headerLength);
                request.dataBuffer.put(remain);
                flag = !request.dataBuffer.hasRemaining();
                if (flag) {
                    dealPostData();
                }
            } else {
                flag = true;
            }
        }
        return flag;
    }

    private void parseHttpProtocolHeader(String[] headerArr, String pHeader) throws Exception {
        String[] protocolHeaderArr = pHeader.split(" ");
        checkHttpMethod();
        request.method = HttpMethod.valueOf(protocolHeaderArr[0]);
        // 先得到请求头信息
        for (int i = 1; i < headerArr.length; i++) {
            dealRequestHeaderString(headerArr[i]);
        }
        String tUrl = request.uri = protocolHeaderArr[1];
        // just for some proxy-client
        if (tUrl.startsWith(request.scheme + "://")) {
            tUrl = tUrl.substring((request.scheme + "://").length());
            request.header.put("Host", tUrl.substring(0, tUrl.indexOf("/")));
            tUrl = tUrl.substring(tUrl.indexOf("/"));
        }
        if (tUrl.contains("?")) {
            request.uri = tUrl.substring(0, tUrl.indexOf("?"));
            request.queryStr = tUrl.substring(tUrl.indexOf("?") + 1);
        } else {
            request.uri = tUrl;
        }
        if (request.uri.contains("/")) {
            request.uri = URLDecoder.decode(request.uri.substring(request.uri.indexOf("/")), "UTF-8");
        } else {
            request.getHeaderMap().put("Host", request.uri);
            request.uri = "/";
        }
    }

    private void dealRequestHeaderString(String str) {
        if (str.contains(":")) {
            request.header.put(str.split(":")[0], str.substring(str.indexOf(":") + 1).trim());
        }
    }


    private void wrapperParamStrToMap(String paramStr) {
        request.paramMap = new HashMap<>();
        if (paramStr != null) {
            Map<String, List<String>> tempParam = new HashMap<>();
            String args[] = paramStr.split("&");
            for (String string : args) {
                int idx = string.indexOf("=");
                if (idx != -1) {
                    String key = string.substring(0, idx);
                    String value = string.substring(idx + 1);
                    if (tempParam.containsKey(key)) {
                        tempParam.get(key).add(value);
                    } else {
                        List<String> paramValues = new ArrayList<>();
                        paramValues.add(value);
                        tempParam.put(key, paramValues);
                    }
                }
            }
            for (Entry<String, List<String>> entry : tempParam.entrySet()) {
                request.paramMap.put(entry.getKey(), entry.getValue().toArray(new String[entry.getValue().size()]));
            }
        }
    }

    private void dealPostData() {
        if (request.header.get("Content-Type") != null && request.header.get("Content-Type").split(";")[0] != null) {
            if ("multipart/form-data".equals(request.header.get("Content-Type").split(";")[0])) {
                //TODO 使用合理算法提高对网卡的利用率
                //FIXME 不支持多文件上传，不支持这里有其他属性字段
                if (!request.dataBuffer.hasRemaining()) {
                    BufferedReader bin = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(request.dataBuffer.array())));
                    //ByteArrayOutputStream bout=new ByteArrayOutputStream(d);
                    StringBuilder sb = new StringBuilder();
                    try {
                        String headerStr;
                        while ((headerStr = bin.readLine()) != null && !"".equals(headerStr)) {
                            sb.append(headerStr).append(CRLF);
                            dealRequestHeaderString(headerStr);
                        }
                    } catch (IOException e) {
                        LOGGER.log(Level.SEVERE, "", e);
                    } finally {
                        try {
                            bin.close();
                        } catch (IOException e) {
                            LOGGER.log(Level.SEVERE, "", e);
                        }
                    }

                    LOGGER.info(request.header.toString());

                    String inputName = request.header.get("Content-Disposition").split(";")[1].split("=")[1].replace("\"", "");
                    String fileName;
                    if (request.header.get("Content-Disposition").split(";").length > 2) {
                        fileName = request.header.get("Content-Disposition").split(";")[2].split("=")[1].replace("\"", "");
                    } else {
                        fileName = randomFile();
                    }
                    File file = new File(PathUtil.getTempPath() + fileName);
                    request.files.put(inputName, file);
                    int length1 = sb.toString().split(CRLF)[0].getBytes().length + CRLF.getBytes().length;
                    int length2 = sb.toString().getBytes().length + 2;
                    int dataLength = Integer.parseInt(request.header.get("Content-Length")) - length1 - length2 - SPLIT.getBytes().length;
                    IOUtil.writeBytesToFile(BytesUtil.subBytes(request.dataBuffer.array(), length2, dataLength), file);
                    request.paramMap = new HashMap<>();
                }
            } else {
                wrapperParamStrToMap(new String(request.dataBuffer.array()));
            }
        } else {
            wrapperParamStrToMap(new String(request.dataBuffer.array()));
        }
    }

    @Override
    public HttpRequest getRequest() {
        return request;
    }
}
