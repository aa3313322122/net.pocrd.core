package net.pocrd.core;

import com.alibaba.fastjson.JSON;
import net.pocrd.annotation.*;
import net.pocrd.define.*;
import net.pocrd.entity.*;
import net.pocrd.responseEntity.*;
import net.pocrd.util.HttpApiProvider;
import net.pocrd.util.POJOSerializerProvider;
import net.pocrd.util.RawString;
import net.pocrd.util.TypeCheckUtil;
import net.pocrd.util.TypeCheckUtil.DescriptionAnnotationChecker;
import net.pocrd.util.TypeCheckUtil.PublicFieldChecker;
import net.pocrd.util.TypeCheckUtil.SerializableImplChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接口管理器，用于管理api的注册过程 目前使用HashMap作为存储容器是因为当前的ApiManager在应用启动时进行静态初始化，能保证线程 安全的进行数据插入操作。在这之后都是对HashMap的多线程只读访问。
 *
 * @author rendong
 */
public final class ApiManager {
    private static final Logger                       logger      = LoggerFactory.getLogger(ApiManager.class);
    private              Map<String, HttpApiExecutor> nameToApi   = new ConcurrentHashMap<String, HttpApiExecutor>();
    private              Map<String, ApiMethodInfo>   apiInfos    = new ConcurrentHashMap<String, ApiMethodInfo>();
    private static final String                       UNDER_SCORE = "_";

    public ApiManager() {
    }

    public void register(List<ApiMethodInfo> apis) {
        register("", apis);
    }

    /**
     * jarfilename用来定位是哪个jar包出的问题
     */
    public void register(final String jarfilename, List<ApiMethodInfo> apis) {
        if (apis == null) {
            return;
        }
        try {
            for (ApiMethodInfo api : apis) {
                if (CompileConfig.isDebug) {
                    if (apiInfos.containsKey(api.methodName)) {
                        throw new RuntimeException("duplicate definision for " + api.methodName);
                    }
                    if (api.state == ApiOpenState.DOCUMENT) {//只需要生成文档的api，不创建代理
                        apiInfos.put(api.methodName, api);
                    }
                }
                if (api.state == ApiOpenState.OPEN || api.state == ApiOpenState.DEPRECATED) {
                    apiInfos.put(api.methodName, api);
                    nameToApi.put(api.methodName, HttpApiProvider.getApiExecutor(api.methodName, api));
                }
            }
        } catch (Throwable t) {
            logger.error("register api failed.jar file name:" + jarfilename, t);
            t.printStackTrace();
        }
    }

    /**
     * 获取某接口信息
     */
    public ApiMethodInfo getApiMethodInfo(String name) {
        ApiMethodInfo methodInfo = apiInfos.get(name);
        if (CompileConfig.isDebug) {
            if (methodInfo != null) {//DOCUMENT 不对外暴露
                if (methodInfo.state == ApiOpenState.DOCUMENT || methodInfo.securityLevel == SecurityType.Document) {
                    return null;
                }
            }
        }
        return methodInfo;
    }

    /**
     * 获取已注册接口
     */
    public ApiMethodInfo[] getApiMethodInfos() {
        return apiInfos.values().toArray(new ApiMethodInfo[apiInfos.size()]);
    }

    /**
     * 处理Api请求
     */
    public final Object processRequest(String name, String[] parameters) {
        return apiInfos.get(name).wrapper.wrap(nameToApi.get(name).execute(parameters));
    }

    /**
     * 是否是常量
     */
    public static boolean isConstField(Field field) {
        int efm = field.getModifiers();
        if (Modifier.isPublic(efm) && Modifier.isStatic(efm) && Modifier.isFinal(efm)) {
            return true;
        }
        return false;
    }

    public static List<ApiMethodInfo> parseApi(Class<?> clazz, Object serviceInstance) {
        ApiGroup groupAnnotation = clazz.getAnnotation(ApiGroup.class);
        if (groupAnnotation == null) {
            return null;
        }
        try {
            int minCode = groupAnnotation.minCode();
            int maxCode = groupAnnotation.maxCode();
            String groupName = groupAnnotation.name();
            Class<?> returnCodeClass = groupAnnotation.codeDefine();
            try {
                for (Field f : returnCodeClass.getDeclaredFields()) {
                    if (isConstField(f) && AbstractReturnCode.class.isAssignableFrom(f.getType())) {
                        AbstractReturnCode code = (AbstractReturnCode)f.get(null);
                        if (code.getCode() < minCode || code.getCode() >= maxCode) {
                            throw new RuntimeException(
                                    "code " + f.getName() + " which value is " + code.getCode() + " not in the scope [" + minCode + "," + maxCode
                                            + ") in " + groupName);
                        }
                        code.setName(f.getName() + UNDER_SCORE + code.getCode());
                        code.setService(groupName);
                        ReturnCodeContainer.putReturnCodeSuper2Map(code);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("parse code failed. " + returnCodeClass.getName() + " in " + groupName, e);
            }
            List<ApiMethodInfo> apis = new LinkedList<ApiMethodInfo>();
            for (Method mInfo : clazz.getMethods()) {
                HttpApi api = mInfo.getAnnotation(HttpApi.class);
                if (api != null) {
                    ApiMethodInfo apiInfo = new ApiMethodInfo();
                    if (CompileConfig.isDebug) {
                        ApiMockReturnObject aro = mInfo.getAnnotation(ApiMockReturnObject.class);
                        if (aro != null) {
                            apiInfo.staticMockValue = aro.value().newInstance();
                            apiInfo.mocked = true;
                            if (!mInfo.getReturnType().isInstance(apiInfo.staticMockValue) || !MockApiReturnObject.class
                                    .isInstance(apiInfo.staticMockValue)) {
                                throw new RuntimeException("mock data type error " + clazz.getName() + " " + api.name());
                            }
                        }
                    }
                    ApiShortCircuit asc = mInfo.getAnnotation(ApiShortCircuit.class);
                    if (asc != null) {
                        apiInfo.staticMockValue = asc.value().newInstance();
                        apiInfo.mocked = true;
                        if (!mInfo.getReturnType().isInstance(apiInfo.staticMockValue) || !MockApiReturnObject.class
                                .isInstance(apiInfo.staticMockValue)) {
                            throw new RuntimeException("short circuit data type error " + clazz.getName() + " " + api.name());
                        }
                    }
                    EncryptTransfer et = mInfo.getAnnotation(EncryptTransfer.class);
                    if (et != null) {
                        apiInfo.encryptionOnly = et.encryptionOnly();
                    }
                    apiInfo.groupName = groupName;
                    apiInfo.description = api.desc();
                    apiInfo.detail = api.detail();
                    apiInfo.methodName = api.name();
                    apiInfo.owner = api.owner();
                    apiInfo.groupOwner = groupAnnotation.owner();
                    Roles roles = mInfo.getAnnotation(Roles.class);
                    if (roles != null && roles.value() != null) {
                        String[] rs = roles.value();
                        apiInfo.roleSet = new HashSet<String>();
                        for (String role : rs) {
                            apiInfo.roleSet.add(role);
                        }
                    }
                    DesignedErrorCode errors = mInfo.getAnnotation(DesignedErrorCode.class);
                    if (errors != null && errors.value() != null) {
                        int[] es = errors.value();
                        int size = es.length;
                        if (size > 0) {
                            apiInfo.errorCodes = new AbstractReturnCode[size];
                            apiInfo.errors = new int[size];
                            for (int i = 0; i < size; i++) {
                                AbstractReturnCode c = ReturnCodeContainer.findCode(es[i]);
                                apiInfo.errorCodes[i] = c;
                                apiInfo.errors[i] = c.getCode();
                            }
                            // 避免重复定义error code
                            HashSet<Integer> set = new HashSet<Integer>();
                            for (int i : es) {
                                if (set.contains(i)) {
                                    throw new RuntimeException("duplicate error code " + i + " in " + clazz.getName() + " " + api.name());
                                } else {
                                    set.add(i);
                                }
                            }
                            // error code 排序
                            Arrays.sort(apiInfo.errorCodes, new Comparator<AbstractReturnCode>() {
                                @Override
                                public int compare(AbstractReturnCode o1, AbstractReturnCode o2) {
                                    return o1.getCode() > o2.getCode() ? 1 : o1.getCode() < o2.getCode() ? -1 : 0;
                                }
                            });
                            Arrays.sort(apiInfo.errors);
                        }
                    }
                    apiInfo.proxyMethodInfo = mInfo;
                    if (!CompileConfig.isDebug) {
                        if (serviceInstance == null) {
                            if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())) {
                                try {
                                    apiInfo.serviceInstance = clazz.newInstance();
                                } catch (Exception e) {
                                    throw new RuntimeException("服务实例化失败" + clazz.getName(), e);
                                }
                            } else {
                                throw new RuntimeException("服务实例不存在" + clazz.getName());
                            }
                        }
                    }
                    apiInfo.serviceInstance = serviceInstance;
                    Class<?>[] parameterTypes = mInfo.getParameterTypes();
                    Annotation[][] parameterAnnotations = mInfo.getParameterAnnotations();
                    if (parameterTypes.length != parameterAnnotations.length) {
                        throw new RuntimeException("存在未被标记的http api参数" + clazz.getName());
                    }
                    ApiParameterInfo[] pInfos = new ApiParameterInfo[parameterTypes.length];
                    for (int i = 0; i < parameterTypes.length; i++) {
                        ApiParameterInfo pInfo = new ApiParameterInfo();
                        pInfo.type = parameterTypes[i];
                        Annotation[] a = parameterAnnotations[i];
                        if (a == null || a.length == 0) {
                            throw new RuntimeException("api参数未被标记" + clazz.getName());
                        }
                        {
                            //入参的递归检查
                            if (Collection.class.isAssignableFrom(pInfo.type)) {//增加对List自定义Object的支持+List<String>的支持
                                if (Collection.class.isAssignableFrom(pInfo.type)) {
                                    Type genericType;
                                    try {
                                        genericType = ((ParameterizedTypeImpl)mInfo.getGenericParameterTypes()[i]).getActualTypeArguments()[0];
                                    } catch (Throwable t) {
                                        throw new RuntimeException("unsupported input type,get genericType failed, method name:" + mInfo.getName(),
                                                t);
                                    }
                                    try {
                                        pInfo.actuallyGenericType = Class
                                                .forName(((Class)genericType).getName(), true, Thread.currentThread().getContextClassLoader());
                                    } catch (Exception e) {
                                        throw new RuntimeException("generic type unsupported:" + genericType + " in " + clazz.getName(), e);
                                    }
                                } else {
                                    throw new RuntimeException("only list is support when using collection, method name:" + mInfo.getName());
                                }
                            }
                            if (a[0].annotationType() != ApiCookieAutowired.class || pInfo.type != Map.class) {
                                TypeCheckUtil.recursiveCheckInputType(clazz.getName(), pInfo.type, pInfo.actuallyGenericType,
                                        new DescriptionAnnotationChecker(), new SerializableImplChecker(),
                                        new PublicFieldChecker());
                            }
                        }
                        for (int j = 0; j < a.length; ) {
                            Annotation n = a[j];
                            if (n.annotationType() == ApiParameter.class) {
                                ApiParameter p = (ApiParameter)n;
                                pInfo.description = p.desc();
                                if (p.sequence() != null && p.sequence().trim().length() > 0) {
                                    pInfo.sequence = p.sequence();
                                }
                                pInfo.isRequired = p.required();
                                pInfo.isRsaEncrypted = p.rsaEncrypted();
                                pInfo.ignoreForSecurity = p.ignoreForSecurity();
                                pInfo.name = p.name();
                                if (p.enumDef() != null && p.enumDef() != EnumNull.class) {
                                    if (pInfo.type == String.class || pInfo.type.getComponentType() == String.class
                                            || pInfo.actuallyGenericType == String.class) {
                                        pInfo.verifyEnumType = p.enumDef();
                                    }
                                }
                                for (Annotation an : a) {
                                    if (an.annotationType() == EnumDef.class) {
                                        if (pInfo.type == String.class || pInfo.type.getComponentType() == String.class
                                                || pInfo.actuallyGenericType == String.class) {
                                            pInfo.verifyEnumType = ((EnumDef)an).value();
                                        }
                                    }
                                }
                                if (CompileConfig.isDebug) {
                                    // 检查sequence属性合法性
                                    if (pInfo.sequence != null && pInfo.sequence.length() > 0) {
                                        try {
                                            if (pInfo.sequence.startsWith("int") || pInfo.sequence.startsWith("str")) {
                                                Integer.parseInt(pInfo.sequence.substring(4));
                                            } else {
                                                throw new RuntimeException("invalid sequence value("
                                                        + pInfo.sequence + ") of api parameter " + mInfo.getName() + "  " + pInfo.name);
                                            }
                                        } catch (Exception e) {
                                            throw new RuntimeException("invalid sequence value("
                                                    + pInfo.sequence + ") of api parameter " + mInfo.getName() + "  " + pInfo.name, e);
                                        }
                                    }
                                    // '_'前缀被用于标识本系统的通用参数
                                    // 除了第三方接口外, 其余接口不能使用'_'打头的参数名
                                    if (!SecurityType.Integrated.check(api.security())) {
                                        if (pInfo.name.startsWith("_")) {
                                            throw new RuntimeException(
                                                    "api parameter name cannot start with '_'" + mInfo.getName() + "  " + pInfo.name);
                                        }
                                    }
                                }
                                pInfo.verifyRegex = p.verifyRegex();
                                if (pInfo.verifyRegex == null) {
                                    pInfo.verifyMsg = null;
                                } else {
                                    if (pInfo.verifyRegex.length() == 0) {
                                        pInfo.verifyRegex = null;
                                        pInfo.verifyMsg = null;
                                    } else {
                                        pInfo.verifyMsg = p.verifyMsg();
                                        if (pInfo.verifyMsg == null) {
                                            throw new RuntimeException(
                                                    "verifyMsg must not null when verifyRegex is not null. method:" + apiInfo.methodName
                                                            + "  parameter:" + pInfo.name);
                                        }
                                    }
                                }
                                try {
                                    if (pInfo.isRequired) {
                                        pInfo.defaultValue = null;
                                    } else {
                                        parseDefaultValue(p.defaultValue(), apiInfo, pInfo);
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(
                                            "parse default value failed. " + pInfo.name + "  " + api.name() + "  " + clazz.getName(), e);
                                }
                                break;
                            } else if (n.annotationType() == ApiAutowired.class) {
                                ApiAutowired p = (ApiAutowired)n;
                                pInfo.name = p.value().name();
                                if (AutowireableParameter.postBody.name().equals(pInfo.name) && parameterTypes.length != 1) {
                                    throw new RuntimeException(
                                            "one parameter only for postBody " + api.name() + "  " + clazz.getName());
                                }
                                pInfo.isAutowired = true;
                                break;
                            } else if (n.annotationType() == DesignedParameter.class) {
                                DesignedParameter p = (DesignedParameter)n;
                                pInfo.creator = (ParamCreator)p.value().newInstance();
                                pInfo.isRequired = true;
                                pInfo.isAutowired = true;
                                break;
                            } else if (n.annotationType() == ApiCookieAutowired.class) {
                                ApiCookieAutowired p = (ApiCookieAutowired)n;
                                if (p.value() == null || p.value().length == 0) {
                                    throw new RuntimeException("cookie名不能为空 " + api.name() + "  " + clazz.getName());
                                }
                                pInfo.name = AutowireableParameter.cookies.name();
                                pInfo.names = p.value();
                                pInfo.isAutowired = true;
                                break;
                            }
                            j++;
                            if (j == a.length) {
                                throw new RuntimeException("api参数未被标记" + api.name() + "  " + clazz.getName());
                            }
                        }
                        pInfos[i] = pInfo;
                    }
                    apiInfo.parameterInfos = pInfos;
                    if (hasDuplicateParam(pInfos)) {
                        throw new RuntimeException("duplicate param , groupName: " + apiInfo.groupName + ", methodName: " + apiInfo.methodName);
                    }
                    parseReturnType(apiInfo, mInfo, clazz);//返回结果解析,设置apiInfo.seriliazer,apiInfo.returnType, apiInfo.actuallyGenericType
                    //递归检查返回结果类型
                    TypeCheckUtil.recursiveCheckReturnType(clazz.getName(), apiInfo.returnType, apiInfo.actuallyGenericReturnType,
                            new SerializableImplChecker(), new DescriptionAnnotationChecker(),
                            new PublicFieldChecker());
                    apiInfo.dubboInterface = clazz;
                    apiInfo.securityLevel = api.security();
                    //对于Integrated级别接口需要指定可访问该接口的第三方编号
                    if (SecurityType.Integrated.check(apiInfo.securityLevel)) {
                        if (api.needVerify()) {
                            apiInfo.needVerfiy = true;
                        } else {
                            // 接口未要求网关进行签名验证
                            apiInfo.needVerfiy = false;
                            logger.warn(
                                    "integrated api:" + apiInfo.methodName
                                            + " do not need verify by apigw. Dubbo service must ensure the legitimacy of the request!");
                        }
                    }
                    apiInfo.state = api.state();
                    apis.add(apiInfo);
                }
            }
            ApiMockInterfaceImpl amii = clazz.getAnnotation(ApiMockInterfaceImpl.class);
            if (amii != null) {
                for (ApiMethodInfo info : apis) {
                    info.mocked = true;
                }
            }
            if (apis.size() == 0) {
                throw new RuntimeException("[API] api method not found. class:" + clazz.getName());
            }
            return apis;
        } catch (Throwable t) {
            logger.error("parse api failed. {}" + clazz.getName(), t);
            if (CompileConfig.isDebug) {
                t.printStackTrace();//为testcase服务,控制台打印错误信息
            }
        }
        return null;
    }

    /**
     * 默认值是否合法的检查
     * 设置默认值
     */
    private static void parseDefaultValue(String defaultValue, ApiMethodInfo apiInfo, ApiParameterInfo pInfo) {
        pInfo.defaultValue = defaultValue;
        if (pInfo.type.isPrimitive()) {
            if ((pInfo.defaultValue == null || pInfo.defaultValue.length() == 0)) {
                pInfo.defaultValue = "0";//未设置默认值,0
            } else {
                if (pInfo.type == boolean.class) {
                    Boolean.parseBoolean(pInfo.defaultValue);
                } else if (pInfo.type == byte.class) {
                    Byte.parseByte(pInfo.defaultValue);
                } else if (pInfo.type == short.class) {
                    Short.parseShort(pInfo.defaultValue);
                } else if (pInfo.type == char.class) {
                    Integer.parseInt(pInfo.defaultValue);
                } else if (pInfo.type == int.class) {
                    Integer.parseInt(pInfo.defaultValue);
                } else if (pInfo.type == long.class) {
                    Long.parseLong(pInfo.defaultValue);
                } else if (pInfo.type == float.class) {
                    Float.parseFloat(pInfo.defaultValue);
                } else if (pInfo.type == double.class) {
                    Double.parseDouble(pInfo.defaultValue);
                }
            }
        } else if (pInfo.type.isEnum()) {
            if (pInfo.defaultValue != null) {
                if (pInfo.defaultValue.length() == 0) {
                    pInfo.defaultValue = null;
                } else {
                    Enum.valueOf((Class<Enum>)pInfo.type, pInfo.defaultValue);
                }
            }
        } else if ((pInfo.type.isArray() && pInfo.type.getComponentType() == String.class) || (Collection.class.isAssignableFrom(pInfo.type)
                && pInfo.actuallyGenericType == String.class)) {
            apiInfo.needDefaultValueConstDefined = true;//需要在ApiExecute设置常量
            pInfo.needDefaultValueConstDefined = true;
            if (pInfo.defaultValue != null && pInfo.defaultValue.length() == 0) {
                pInfo.defaultValue = null;//未设置String的默认值,默认值为null
            } else if (pInfo.verifyEnumType != null && pInfo.verifyEnumType != EnumNull.class) {
                List<String> sa = JSON.parseArray(pInfo.defaultValue, String.class);
                for (String s : sa) {
                    Enum.valueOf(pInfo.verifyEnumType, s);
                }
            }
        } else if (pInfo.type != String.class) {
            apiInfo.needDefaultValueConstDefined = true;//需要在ApiExecute设置常量
            pInfo.needDefaultValueConstDefined = true;
            if (pInfo.defaultValue != null) {
                if (pInfo.defaultValue.length() == 0) {
                    pInfo.defaultValue = null;//结构化入参默认值为null
                } else {
                    JSON.parseObject(pInfo.defaultValue, pInfo.type);//测试结构化参数
                }
            }
        } else if (pInfo.type == String.class) {
            if (pInfo.defaultValue != null && pInfo.defaultValue.length() == 0) {
                pInfo.defaultValue = null;//未设置String的默认值,默认值为null
            } else if (pInfo.verifyEnumType != null && pInfo.verifyEnumType != EnumNull.class) {
                Enum.valueOf(pInfo.verifyEnumType, pInfo.defaultValue);
            }
        }
    }

    /**
     * 解析返回结果,为每个api设定对应的serializer
     *
     * @param apiInfo api信息,保存了接口对应的处理实例、serializer等信息
     * @param mInfo   反射获得的方法信息
     * @param clazz   对应的dubbo service interface class
     */
    private static void parseReturnType(ApiMethodInfo apiInfo, Method mInfo, Class clazz) {
        apiInfo.returnType = mInfo.getReturnType();
        apiInfo.wrapper = ResponseWrapper.objectWrapper;
        //返回结果分析
        if (String.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(StringResp.class);
            apiInfo.wrapper = ResponseWrapper.stringWrapper;
        } else if (String[].class == apiInfo.returnType) {//不适用ObjectArrayResp，考虑到代码生成的时候会生成重复代码
            apiInfo.serializer = POJOSerializerProvider.getSerializer(StringArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.stringArrayWrapper;
        } else if (Date.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(StringResp.class);
            apiInfo.wrapper = ResponseWrapper.dateWrapper;
        } else if (Date[].class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(StringArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.dateArrayWrapper;
        } else if (boolean.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(BoolResp.class);
            apiInfo.wrapper = ResponseWrapper.boolWrapper;
        } else if (boolean[].class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(BoolArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.boolArrayWrapper;
        } else if (byte.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(NumberResp.class);
            apiInfo.wrapper = ResponseWrapper.byteWrapper;
        } else if (short.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(NumberResp.class);
            apiInfo.wrapper = ResponseWrapper.shortWrapper;
        } else if (char.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(NumberResp.class);
            apiInfo.wrapper = ResponseWrapper.charWrapper;
        } else if (int.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(NumberResp.class);
            apiInfo.wrapper = ResponseWrapper.intWrapper;
        } else if (byte[].class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(NumberArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.byteArrayWrapper;
        } else if (short[].class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(NumberArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.shotArrayWrapper;
        } else if (char[].class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(NumberArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.charArrayWrapper;
        } else if (int[].class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(NumberArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.intArrayWrapper;
        } else if (long.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(LongResp.class);
            apiInfo.wrapper = ResponseWrapper.longWrapper;
        } else if (long[].class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(LongArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.longArrayWrapper;
        } else if (double.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(DoubleResp.class);
            apiInfo.wrapper = ResponseWrapper.doubleWrapper;
        } else if (float.class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(DoubleResp.class);
            apiInfo.wrapper = ResponseWrapper.floatWrapper;
        } else if (double[].class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(DoubleArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.doubleArrayWrapper;
        } else if (float[].class == apiInfo.returnType) {
            apiInfo.serializer = POJOSerializerProvider.getSerializer(DoubleArrayResp.class);
            apiInfo.wrapper = ResponseWrapper.floatArrayWrapper;
        } else if (JSONString.class == apiInfo.returnType) {
            apiInfo.serializer = Serializer.jsonStringSerializer;
            apiInfo.wrapper = ResponseWrapper.objectWrapper;
        } else if (RawString.class == apiInfo.returnType) {
            apiInfo.serializer = Serializer.rawStringSerializer;
            apiInfo.wrapper = ResponseWrapper.objectWrapper;
        } else if (Collection.class.isAssignableFrom(apiInfo.returnType)) {//增加对Collection自定义Object的支持+Collection<String>的支持
            Type genericType;
            try {
                genericType = ((ParameterizedTypeImpl)mInfo.getGenericReturnType()).getActualTypeArguments()[0];
            } catch (Throwable t) {
                throw new RuntimeException("unsupported return type, get genericType failed, method name:" + mInfo.getName(), t);
            }
            Class<?> genericClazz;
            try {
                genericClazz = Class.forName(((Class)genericType).getName(), true, Thread.currentThread().getContextClassLoader());
            } catch (Exception e) {
                throw new RuntimeException("generic type load failed:" + genericType + " in " + clazz.getName() + " method name:" + mInfo.getName(),
                        e);
            }
            if (String.class == genericClazz) {//如果要支持更多的jdk中已有类型的序列化
                apiInfo.serializer = POJOSerializerProvider.getSerializer(StringArrayResp.class);
                apiInfo.wrapper = ResponseWrapper.stringCollectionWrapper;
            } else if (genericClazz.getAnnotation(Description.class) != null) {
                apiInfo.serializer = Serializer.objectArrayRespSerializer;
                apiInfo.wrapper = ResponseWrapper.objectCollectionWrapper;
            } else if (Date.class == genericClazz) {
                apiInfo.serializer = POJOSerializerProvider.getSerializer(DateArrayResp.class);
                apiInfo.wrapper = ResponseWrapper.dateCollectionWrapper;
            } else {
                throw new RuntimeException("unsupported return type, genericType:" + genericClazz.getName() + " method name:" + mInfo.getName());
            }
            apiInfo.actuallyGenericReturnType = genericClazz;
        } else if (apiInfo.returnType.isArray()) {//TODO 自定义数组的支持
            throw new RuntimeException("unsupported return type, method name:" + mInfo.getName());
        } else {
            Description desc = apiInfo.returnType.getAnnotation(Description.class);
            if (desc == null) {
                throw new RuntimeException(
                        "unsupported return type:" + apiInfo.returnType.getName() + ", miss description method name:" + mInfo.getName());
            }
            apiInfo.serializer = POJOSerializerProvider.getSerializer(apiInfo.returnType);
            apiInfo.wrapper = ResponseWrapper.objectWrapper;
        }
    }

    /**
     * 检查接口参数定义中是否有重复的参数命名
     *
     * @param pInfos 参数信息
     *
     * @return true:存在重复的参数名,false:无重复参数
     */
    private static boolean hasDuplicateParam(ApiParameterInfo[] pInfos) {
        HashSet<String> hs = new HashSet<String>();
        if (pInfos != null) {
            for (ApiParameterInfo pInfo : pInfos) {
                if (!hs.contains(pInfo.name)) {
                    hs.add(pInfo.name);
                } else {
                    return true;
                }
            }
        }
        return false;
    }
}
