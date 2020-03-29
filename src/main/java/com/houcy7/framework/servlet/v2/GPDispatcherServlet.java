package com.houcy7.framework.servlet.v2;

import com.houcy7.framework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GPDispatcherServlet extends HttpServlet {

    private static final String CONTEXT_CONFIG_LOCATION = "contextConfigLocation";
    private static final String SCAN_PACKAGE = "scanPackage";

    //存储aplication.properties的配置内容
    private Properties contextConfig = new Properties();
    //存储所有扫描到的类
    private List<String> classNames = new ArrayList<String>();
    //IOC容器，保存所有实例化对象
    //注册式单例模式
    private Map<String, Object> ioc = new HashMap<String, Object>();
    //保存所有的Url和方法的映射关系
    private List<Handler> handlerMapping = new ArrayList<Handler>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //派遣，分发任务
        try {
            //委派模式
            doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exception,Details:\r\n" + Arrays.toString(e.getStackTrace()).replaceAll("\\[|\\]", "").replaceAll(",\\s", "\r\n"));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        Handler handler = getHandler(req);
        if (null == handler) {
            resp.getWriter().write("404 not found");
            return;
        }

        //获取方法的参数列表
        Class<?>[] paramTypes = handler.method.getParameterTypes();

        //保存所有需要自动赋值的参数值
        Object[] paramValues = new Object[paramTypes.length];

        Map<String, String[]> params = req.getParameterMap();
        for (Map.Entry<String, String[]> param : params.entrySet()) {
            String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").replaceAll(",\\s", ",");

            //如果找到匹配的对象，则开始填充参数值
            if (!handler.paramIndexMapping.containsKey(param.getKey())) {
                continue;
            }
            int index = handler.paramIndexMapping.get(param.getKey());
            paramValues[index] = convert(paramTypes[index], value);
        }

        //设置方法中的request和response对象
        if (handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())) {
            int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
        }

        if (handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())) {
            int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[respIndex] = resp;
        }

        Object invoke = handler.method.invoke(handler.controller, paramValues);
        resp.setCharacterEncoding("gbk");
        resp.getWriter().write(invoke.toString());
    }

    //url传过来的参数都是String类型的，HTTP是基于字符串协议
    //只需要把String转换为任意类型就好
    private Object convert(Class<?> type, String value) {
        if (Integer.class == type) {
            return Integer.valueOf(value);
        } else if (Double.class == type) {
            return Double.valueOf(value);
        }
        //如果还有double或者其他类型，继续加if
        return value;
    }

    private Handler getHandler(HttpServletRequest req) {
        if (handlerMapping.isEmpty()) return null;

        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");

        for (Handler handler : handlerMapping) {
            Matcher matcher = handler.getUrl().matcher(url);
            if (matcher.matches())
                return handler;
        }
        return null;
    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        //模板模式
        //1、加载配置文件
        doLoadConfig(config.getInitParameter(CONTEXT_CONFIG_LOCATION));
        //2、扫描相关的类
        doScanner(contextConfig.getProperty(SCAN_PACKAGE));
        //3、初始化所有相关的类的实例，并且放入到IOC容器之中
        doInstance();
        //4、完成依赖注入
        doAutowired();
        //5、初始化HandlerMapping
        initHandlerMapping();

        System.out.println("GP Spring framework is init.");
    }

    private void initHandlerMapping() {
        // 如果ioc容器为空的话 直接返回
        if (ioc.isEmpty()) return;
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(GPController.class)) continue;

            String baseUrl = "";
            // 获取Controller的url配置
            if (clazz.isAnnotationPresent(GPRequestMapping.class)) {
                GPRequestMapping requestMapping = clazz.getAnnotation(GPRequestMapping.class);
                baseUrl = requestMapping.value();
            }

            // 获取method的配置
            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                //没有加RequestMapping注解的直接忽略
                if (!method.isAnnotationPresent(GPRequestMapping.class)) {
                    continue;
                }

                //映射URL
                GPRequestMapping requestMapping = method.getAnnotation(GPRequestMapping.class);
                //  /demo/query
                //  (//demo//query)

                // 拼接的时候 为了避免出现多个 "//" 使用正则进行替换
                String url = ("/" + baseUrl + "/" + requestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(url);
                handlerMapping.add(new Handler(entry.getValue(), method, pattern));
                System.out.println("Mapped " + url + "," + method);
            }

        }
    }

    private void doAutowired() {
        // 如果容器中没有bean的时候 直接返回
        if (ioc.isEmpty()) return;
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            // 获取实例对象的所有属性
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            // 遍历所有属性 对含有 GPAutowired 注解的属性 进行注入
            for (Field field : fields) {
                if (!field.isAnnotationPresent(GPAutowired.class)) {
                    continue;
                }
                GPAutowired annotation = field.getAnnotation(GPAutowired.class);
                // 判断注入的时候有没有指定名字 没有指定名字 为类名称
                String beanName = "".equals(annotation.value().trim()) ? field.getType().getName() : annotation.value().trim();
                // 开启访问
                field.setAccessible(true);
                // 执行注入！！！
                try {
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void doInstance() {
        // 如果一个扫描到的类都没有 直接返回
        if (classNames.isEmpty()) return;
        try {
            for (String className : classNames) {
                // 加载类
                Class<?> clazz = Class.forName(className);

                //判断注解
                if (clazz.isAnnotationPresent(GPController.class)) {
                    Object instance = clazz.newInstance();
                    // 类名称首字母转小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, instance);
                } else if (clazz.isAnnotationPresent(GPService.class)) {
                    //1、默认的类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());

                    //2、自定义命名
                    GPService service = clazz.getAnnotation(GPService.class);
                    if (!"".equals(service.value())) {
                        beanName = service.value();
                    }
                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);

                    //3、根据类型注入实现类，投机取巧的方式
                    for (Class<?> i : clazz.getInterfaces()) {
                        if (ioc.containsKey(i.getName())) {
                            throw new Exception("The beanName is exists!!");
                        }
                        ioc.put(i.getName(), instance);
                    }
                }

            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String toLowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }

    private void doScanner(String scanPackage) {
        // 将配置的路径 com.houcy7.demo 转换为 /com/houcy7/demo
        String baseUrl = "/" + scanPackage.replaceAll("\\.", "/");
        // 获取绝对路径
        URL url = this.getClass().getClassLoader().getResource(baseUrl);

        // 获取文件
        if (null == url || null == url.getFile()) return;
        File classPath = new File(url.getFile());

        for (File file : classPath.listFiles()) {
            if (file.isDirectory()) {
                doScanner(scanPackage + "." + file.getName());
            } else {
                // 如果后缀名不为 .class 的跳过
                if (!file.getName().endsWith(".class")) continue;
                // 拼接类名进行保存
                String className = scanPackage + "." + file.getName().replace(".class", "");
                classNames.add(className);
            }
        }
    }

    private void doLoadConfig(String contextConfigLocation) {
        InputStream inputStream = null;
        try {
            inputStream = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
            contextConfig.load(inputStream);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (null != inputStream) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class Handler {
        private Object controller;
        private Method method;
        private Pattern url;
        private Map<String, Integer> paramIndexMapping;

        public Handler(Object controller, Method method, Pattern url) {
            this.controller = controller;
            this.method = method;
            this.url = url;

            this.paramIndexMapping = new HashMap<>();
            initParamIndexMapping(this.method);
        }

        private void initParamIndexMapping(Method method) {
            // 此处为二维数组 一个方法可能有多个参数 一个参数可能有多个注解
            Annotation[][] annotations = method.getParameterAnnotations();
            // 通过遍历二维数组 获取加了注解的参数
            for (int i = 0; i < annotations.length; i++) {
                for (Annotation annotation : annotations[i]) {
                    if (annotation instanceof GPRequestParam) {
                        String paramName = ((GPRequestParam) annotation).value();
                        if (!"".equals(paramName.trim())) {
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }

            // 获取参数中 response或request的参数
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int i = 0; i < parameterTypes.length; i++) {
                Class<?> parameterType = parameterTypes[i];
                if (parameterType == HttpServletRequest.class || parameterType == HttpServletResponse.class) {
                    paramIndexMapping.put(parameterType.getName(), i);
                }
            }

        }

        public Object getController() {
            return controller;
        }

        public void setController(Object controller) {
            this.controller = controller;
        }

        public Method getMethod() {
            return method;
        }

        public void setMethod(Method method) {
            this.method = method;
        }

        public Pattern getUrl() {
            return url;
        }

        public void setUrl(Pattern url) {
            this.url = url;
        }

        public Map<String, Integer> getParamIndexMapping() {
            return paramIndexMapping;
        }

        public void setParamIndexMapping(Map<String, Integer> paramIndexMapping) {
            this.paramIndexMapping = paramIndexMapping;
        }
    }
}
