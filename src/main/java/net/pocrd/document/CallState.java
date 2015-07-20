package net.pocrd.document;

import net.pocrd.annotation.Description;

/**
 * Created by rendong on 14-5-2.
 */
@Description("调用状态")
public class CallState {
    @Description("返回值")
    public int code;

    @Description("数据长度")
    public int length;

    @Description("返回信息")
    public String msg;
}
