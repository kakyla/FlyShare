package com.tvflyshare.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tvflyshare.app.interconnect.DeviceDiscoveryManager
import com.tvflyshare.app.interconnect.DeviceModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DeviceListViewModel(application: Application) : AndroidViewModel(application) {
    
    // 说明：TransferForegroundService 托管本机 HTTP 服务与 UDP 发现，是互联连接的基座。
    // 服务持有者（OWNER_DISCOVERY / OWNER_REMOTE_BROWSE）已移至对应 composable 的
    // DisposableEffect 中按"界面可见性"登记/注销，因此本 ViewModel 不再直接控制服务生命周期。
    // 这样进入对端浏览（RemoteBrowse）时设备列表界面离开组合、持有者立即释放，
    // 退出整个互联流程后 30s 自动关闭（与互传一致），不受返回栈保活影响。

    // 监听底层设备发现管理器的状态流
    val discoveredDevices: StateFlow<List<DeviceModel>> = DeviceDiscoveryManager.discoveredDevices
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 手动添加对端设备（跨网段互联）。
     *
     * 仅传 IP，端口由 [DeviceDiscoveryManager] 取本机 HTTP 服务端口（默认 5566）。
     */
    fun addManualDevice(ip: String) {
        DeviceDiscoveryManager.addManualDevice(ip)
    }
}
