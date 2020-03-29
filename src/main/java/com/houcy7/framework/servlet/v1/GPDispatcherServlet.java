package com.houcy7.framework.servlet.v1;

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
    //保存Controller中所有Mapping的对应关系
    private Map<String, Method> handlerMapping = new HashMap<String, Method>();

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
            resp.getWriter().write("500 Excetion Detail:" + Arrays.toString(e.getStackTrace()));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws IOException, InvocationTargetException, IllegalAccessException {
        // 获取请求url 没有找到返回404状态码
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");
        if (!this.handlerMapping.containsKey(url)) {
            resp.getWriter().write("404 Not Found!!");
            return;
        }

        // 从handlerMapper中获取方法
        Method method = this.handlerMapping.get(url);
        // 请求中携带的实参
        Map<String, String[]> params = req.getParameterMap();
        // 方法上的形参
        Class<?>[] parameterTypes = method.getParameterTypes();
        //保存请求的url参数列表
        Map<String, String[]> parameterMap = req.getParameterMap();
        //保存赋值参数的位置
        Object[] paramValues = new Object[parameterTypes.length];
        //按根据参数位置动态赋值
        for (int i = 0; i < parameterTypes.length; i++) {
            Class parameterType = parameterTypes[i];
            if (parameterType == HttpServletRequest.class) {
                paramValues[i] = req;
            } else if (parameterType == HttpServletResponse.class) {
                paramValues[i] = resp;
            } else if (parameterType == String.class) {

                //提取方法中加了注解的参数
                Annotation[][] pa = method.getParameterAnnotations();
                for (int j = 0; j < pa.length; j++) {
                    for (Annotation a : pa[i]) {
                        if (a instanceof GPRequestParam) {
                            String paramName = ((GPRequestParam) a).value();
                            if (!"".equals(paramName.trim())) {
                                String value = Arrays.toString(parameterMap.get(paramName))
                                        .replaceAll("\\[|\\]", "")
                                        .replaceAll("\\s", ",");
                                paramValues[i] = value;
                            }
                        }
                    }
                }
            }
        }
        //投机取巧的方式
        //通过反射拿到method所在class，拿到class之后还是拿到class的名称
        //再调用toLowerFirstCase获得beanName
        String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
        if(null == params || params.isEmpty() || null == params.get("name")[0]){
            method.invoke(ioc.get(beanName), req,resp);
        } else {
            method.invoke(ioc.get(beanName), req,resp,params.get("name")[0]);
        }
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
                handlerMapping.put(url, method);
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
}
