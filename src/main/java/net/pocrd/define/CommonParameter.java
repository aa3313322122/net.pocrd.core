package net.pocrd.define;

import net.pocrd.annotation.Description;
import net.pocrd.core.ApiManager;
import net.pocrd.entity.CompileConfig;

import java.lang.reflect.Field;
import java.util.HashSet;

@Description("api通用参数参数")
public final class CommonParameter {

    @Description("format 返回值格式,取值为枚举SerializeType中的定义,取值范围JSON/XML")
    public static final String format = "_ft";

    @Description("location 用于返回信息国际化,目前尚不支持国际化")
    public static final String location = "_lo";

    @Description("user token 代表访问者身份,完成用户登入流程后获取")
    public static final String token = "_tk";

    @Description("device token 代表访问设备的身份,完成设备注册流程后获取")
    public static final String deviceToken = "_dtk";

    @Description("method 请求的资源名")
    public static final String method = "_mt";

    @Description("signature 参数字符串签名")
    public static final String signature = "_sig";

    @Description("application id 应用编号,访问查看具体定义http://10.0.128.132:8090/pages/viewpage.action?pageId=4457550")
    public static final String applicationId = "_aid";

    @Description("call id 客户端调用编号")
    public static final String callId = "_cid";

    @Description("device id 设备标示符")
    public static final String deviceId = "_did";

    @Description("device id 设备标示符, 存储在cookie中的名字")
    public static final String cookieDeviceId = "__da";

    @Description("user id 用户标示符")
    public static final String userId = "_uid";

    @Description("client ip 用户ip")
    public static final String clientIp = "_cip";

    @Description("version code 客户端数字版本号")
    public static final String versionCode = "_vc";

    @Description("signature method 签名算法 hmac,md5,sha1,rsa,ecc")
    public static final String signatureMethod = "_sm";

    @Description("动态密码验证对应的手机号")
    public static final String phoneNumber = "_pn";

    @Description("动态密码验证对应的动态码")
    public static final String dynamic = "_dyn";

    @Description("jsonp callback名")
    public static final String jsonpCallback = "_cb";

    @Description("第三方集成的身份标识(第三方集成情景下使用)")
    public static final String thirdPartyId = "_tpid";

    @Description("cookie注入 研发专用")
    public static final String cookie = "_cookie";

    /**
     * 用于内部参数注入的标识符，用于指示在第三方集成的场景下网关向后台传递整个post表单
     */
    @Description("用于内部参数注入的标识符，用于指示在第三方集成的场景下网关向后台传递整个post表单")
    public static final String postBody = "_pb";

    private static final HashSet<String> names = new HashSet<String>();

    public static boolean contains(String name) {
        return names.contains(name);
    }

    static {
        if (CompileConfig.isDebug) {
            try {
                Field[] fs = CommonParameter.class.getDeclaredFields();
                if (fs != null) {
                    for (Field f : fs) {
                        if (ApiManager.isConstField(f) && f.getType() == String.class) {
                            names.add((String)f.get(null));
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("load names failed.", e);
            }
        }
    }
}
