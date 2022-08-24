package sakura.kooi.VirtualGraphicTablets.server.core.networking;

import com.sunnysidesoft.VirtualTablet.core.VTService.*;
import lombok.Cleanup;
import lombok.CustomLog;
import lombok.Getter;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import sakura.kooi.VirtualGraphicTablets.server.core.VTabletServer;

import java.util.concurrent.LinkedBlockingQueue;

@CustomLog
public class UpstreamWorker extends Thread {
    private VTabletServer parent;

    @Getter
    protected LinkedBlockingQueue<VTPenEvent> mQueue = new LinkedBlockingQueue<>();
    private VTService.Client mClient;


    public UpstreamWorker(VTabletServer parent) {
        this.parent = parent;
    }

    @Override
    public void run() {
        try {
            @Cleanup TSocket mTransport = new TSocket("127.0.0.1", VTServiceConstants.SERVER_PORT, 5000);

            mTransport.open();
            this.mClient = new VTService.Client(new TBinaryProtocol(mTransport));
                VTServerInfo checkVersion = this.mClient.checkVersion(VTServiceConstants.SERVICE_VERSION);
                if (!checkVersion.isVersionMatched) {
                    log.e("VirtualTablet Server 连接失败: 协议版本不匹配");
                    mTransport.close();
                    parent.onUpstreamDisconnected();
                } else if (this.isInterrupted()) {
                } else {
                    log.s("VirtualTablet Server 连接成功", checkVersion.serverVersion, checkVersion.getScreenWidth(), checkVersion.getScreenHeight());
                    parent.onUpstreamConnected(checkVersion.serverVersion, checkVersion.getScreenWidth(), checkVersion.getScreenHeight());
                    while (!this.isInterrupted()) {
                        VTPenEvent vTPenEvent;
                        try {
                            vTPenEvent = this.mQueue.take();
                        } catch (InterruptedException unused) {
                            break;
                        }
                        try {
                            if ((vTPenEvent.statusMask & ((byte) VTPenStatusMask.HOVER_EXIT.getValue())) == 0) {
                                this.mClient.sendPenEventCompact(((long) vTPenEvent.statusMask << 48) | ((long) vTPenEvent.dx << 32) | (vTPenEvent.dy << 16) | vTPenEvent.pressure);
                            } else {
                                this.mClient.sendPenHoverExit();
                            }
                        } catch (TException e) {
                            log.w("向 Upstream 发送轨迹出错" , e);
                        }
                    }

                    try {
                        this.mClient.closeConnection();
                        mTransport.close();
                        log.w("VirtualTablet Server 断开连接");
                        parent.onUpstreamDisconnected();
                    } catch (TException e2) {
                        mTransport.close();
                        log.e("VirtualTablet Server 断开连接", e2);
                        parent.onUpstreamDisconnected();
                    }
                }
        } catch (Exception e) {
            log.e("An error occurred while communicating with upstream.", e);
        }
    }
}
