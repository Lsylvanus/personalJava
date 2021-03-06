package com.lsylvanus.download;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import com.lsylvanus.entity.DownloadInfo;
import com.lsylvanus.util.LogUtils;

/**
 * 这个类主要是完成读取指定url资源的内容，获取该资源的长度。
 * 然后将该资源分成指定的块数，将每块的起始下载位置、结束下载位置，分别保存在一个数组中。
 * 每块都单独开辟一个独立线程开始下载。
 * 在开始下载之前，需要创建一个临时文件，写入当前下载线程的开始下载指针位置和结束下载指针位置。
 * @author fx-pro
 *
 */
public class BatchDownloadFile implements Runnable{

	//下载文件信息 
    private DownloadInfo downloadInfo;
    //一组开始下载位置
    private long[] startPos;
    //一组结束下载位置
    private long[] endPos;
    //休眠时间
    private static final int SLEEP_SECONDS = 500;
    //子线程下载
    private DownloadFile[] fileItem;
    //文件长度
    private int length;
    //是否第一个文件
    private boolean first = true;
    //是否停止下载
    private boolean stop = false;
    //临时文件信息
    private File tempFile;
	
    public BatchDownloadFile(DownloadInfo downloadInfo) {
        this.downloadInfo = downloadInfo;
        String tempPath = this.downloadInfo.getFilePath() + File.separator + downloadInfo.getFileName() + ".position";
        tempFile = new File(tempPath);
        //如果存在读入点位置的文件
        if (tempFile.exists()) {
            first = false;
            //就直接读取内容
            try {
                readPosInfo();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            //数组的长度就要分成多少段的数量
            startPos = new long[downloadInfo.getSplitter()];
            endPos = new long[downloadInfo.getSplitter()];
        }
    }
    
    @Override
    public void run() {
        //首次下载，获取下载文件长度
        if (first) {
            length = this.getFileSize();//获取文件长度
            if (length == -1) {
                LogUtils.log("file length is know!");
                stop = true;
            } else if (length == -2) {
                LogUtils.log("read file length is error!");
                stop = true;
            } else if (length > 0) {
                /**
                 * eg 
                 * start: 1, 3, 5, 7, 9
                 * end: 3, 5, 7, 9, length
                 */
                for (int i = 0, len = startPos.length; i < len; i++) {
                    int size = i * (length / len);
                    startPos[i] = size;
                    
                    //设置最后一个结束点的位置
                    if (i == len - 1) {
                        endPos[i] = length;
                    } else {
                        size = (i + 1) * (length / len);
                        endPos[i] = size;
                    }
                    LogUtils.log("start-end Position[" + i + "]: " + startPos[i] + "-" + endPos[i]);
                }
            } else {
                LogUtils.log("get file length is error, download is stop!");
                stop = true;
            }
        }
        
        //子线程开始下载
        if (!stop) {
            //创建单线程下载对象数组
            fileItem = new DownloadFile[startPos.length];//startPos.length = downloadInfo.getSplitter()
            for (int i = 0; i < startPos.length; i++) {
                try {
                    //创建指定个数单线程下载对象，每个线程独立完成指定块内容的下载
                    fileItem[i] = new DownloadFile(
                        downloadInfo.getUrl(), 
                        this.downloadInfo.getFilePath() + File.separator + downloadInfo.getFileName(), 
                        startPos[i], endPos[i], i
                    );
                    fileItem[i].start();//启动线程，开始下载
                    LogUtils.log("Thread: " + i + ", startPos: " + startPos[i] + ", endPos: " + endPos[i]);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            
            //循环写入下载文件长度信息
            while (!stop) {
                try {
                    writePosInfo();
                    LogUtils.log("downloading……");
                    Thread.sleep(SLEEP_SECONDS);
                    stop = true;
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for (int i = 0; i < startPos.length; i++) {
                    if (!fileItem[i].isDownloadOver()) {
                        stop = false;
                        break;
                    }
                }
            }
            LogUtils.info("Download task is finished!");
        }
    }
    
    /**
     * 将写入点数据保存在临时文件中
     * @throws IOException
     */
    private void writePosInfo() throws IOException {
        DataOutputStream dos = new DataOutputStream(new FileOutputStream(tempFile));
        dos.writeInt(startPos.length);
        for (int i = 0; i < startPos.length; i++) {
            dos.writeLong(fileItem[i].getStartPos());
            dos.writeLong(fileItem[i].getEndPos());
            //LogUtils.info("[" + fileItem[i].getStartPos() + "#" + fileItem[i].getEndPos() + "]");
        }
        dos.close();
    }
    
    /**
     * 读取写入点的位置信息
     * @throws IOException
     */
    private void readPosInfo() throws IOException {
        DataInputStream dis = new DataInputStream(new FileInputStream(tempFile));
        int startPosLength = dis.readInt();
        startPos = new long[startPosLength];
        endPos = new long[startPosLength];
        for (int i = 0; i < startPosLength; i++) {
            startPos[i] = dis.readLong();
            endPos[i] = dis.readLong();
        }
        dis.close();
    }
    
    /**
     * 获取下载文件的长度
     * @return fileLength
     */
    private int getFileSize() {
        int fileLength = -1;
        try {
            URL url = new URL(this.downloadInfo.getUrl());
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            
            DownloadFile.setHeader(con);
 
            int stateCode = con.getResponseCode();
            //判断http status是否为HTTP/1.1 206 Partial Content或者200 OK
            if (stateCode != HttpURLConnection.HTTP_OK && stateCode != HttpURLConnection.HTTP_PARTIAL) {
                LogUtils.log("Error Code: " + stateCode);
                return -2;
            } else if (stateCode >= 400) {
                LogUtils.log("Error Code: " + stateCode);
                return -2;
            } else {
                //获取长度
                fileLength = con.getContentLength();
                LogUtils.log("FileLength: " + fileLength);
            }
            
            //读取文件长度
            /*for (int i = 1; ; i++) {
                String header = con.getHeaderFieldKey(i);
                if (header != null) {
                    if ("Content-Length".equals(header)) {
                        fileLength = Integer.parseInt(con.getHeaderField(i));
                        break;
                    }
                } else {
                    break;
                }
            }
            */
            
            DownloadFile.printHeader(con);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return fileLength;
    }
}
