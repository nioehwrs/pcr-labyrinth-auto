package com.lmj.labyrinthauto.reroll

/**
 * 刷开局成功后保存在内存里，供 [com.lmj.labyrinthauto.logic.MazeLoop] 在开局时直接消费：
 *  - [RerollResult.Success.bossByArea] 里 3/5 区 Boss 的 unitId → 直接喂给 MazeLoop 的 bossByArea，
 *    彻底消除「开局读不到 3 区 Boss」的差异；
 *  - [RerollResult.Success.routePerArea] → 传给 Route.refreshRoute(savedRoute=…)，让局内直接沿服务器算出的最优路线推进。
 *
 * 与 PC 一致：本对象在单进程内有效（App 前台服务与界面同进程），无需落盘即可被 MazeLoop 读到。
 */
object RerollState {
    var result: RerollResult.Success? = null

    fun clear() {
        result = null
    }
}
