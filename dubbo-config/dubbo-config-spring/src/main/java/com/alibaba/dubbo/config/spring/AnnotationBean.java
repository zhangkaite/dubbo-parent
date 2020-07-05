/*
 * Copyright 1999-2012 Alibaba Group.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config.spring;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.config.AbstractConfig;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.ModuleConfig;
import com.alibaba.dubbo.config.MonitorConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;
import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.annotation.Service;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;

/**
 * AnnotationBean实现了很多spring的特殊bean接口：DisposableBean,BeanFactoryPostProcessor,BeanPostProcessor,
 * ApplicationContextAware。这保证AnnotationBean能够在spring加载的各个时期实现自己的功能。
 * 注解扫描的功能在beanfactory初始化完成调用接口BeanFactoryPostProcessor.postProcessBeanFactory中实现。
 * <p>
 * BeanFactoryPostProcessor 实现该接口，可以在spring的bean创建之前，修改bean的定义属性。也就是说，
 * Spring允许BeanFactoryPostProcessor在容器实例化任何其它bean
 * 之前读取配置元数据，并可以根据需要进行修改，例如可以把bean的scope从singleton改为prototype，也可以把property的值给修改掉。可以同时配置多个BeanFactoryPostProcessor
 * ，并通过设置'order'属性来控制各个BeanFactoryPostProcessor的执行次序。
 * 注意：BeanFactoryPostProcessor是在spring容器加载了bean的定义文件之后，在bean实例化之前执行的。接口方法的入参是ConfigurrableListableBeanFactory
 * ，使用该参数，可以获取到相关bean的定义信息，
 *
 * <p>
 * AnnotationBean实现了spring bean和context相关的接口，在spring扫描完注解类，并解析完时调用 export() 方法对服务进行暴露
 */
public class AnnotationBean extends AbstractConfig implements DisposableBean, BeanFactoryPostProcessor,
        BeanPostProcessor, ApplicationContextAware {

    private static final long serialVersionUID = -7582802454287589552L;

    private static final Logger logger = LoggerFactory.getLogger(Logger.class);

    private String annotationPackage;

    private String[] annotationPackages;

    private final Set<ServiceConfig<?>> serviceConfigs = new ConcurrentHashSet<ServiceConfig<?>>();

    private final ConcurrentMap<String, ReferenceBean<?>> referenceConfigs = new ConcurrentHashMap<String,
            ReferenceBean<?>>();

    public String getPackage() {
        return annotationPackage;
    }

    public void setPackage(String annotationPackage) {
        this.annotationPackage = annotationPackage;
        this.annotationPackages = (annotationPackage == null || annotationPackage.length() == 0) ? null :
                Constants.COMMA_SPLIT_PATTERN.split(annotationPackage);
    }

    private ApplicationContext applicationContext;

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /***
     。 AnnotationBean的参数annotationPackage，就是在beandefinition创建时，从xml中读取到spring中。
     * 源码通过ClassPathBeanDefinitionScanner.doScan扫描annotationPackage下所有的文件。
     * 配置成bean的类会定义成BeanDefinition，注册到spring。
     *  扫描包下面的@Service注解的类，然后将类注入到ioc容器中
     * @param beanFactory
     * @throws BeansException
     */
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (annotationPackage == null || annotationPackage.length() == 0) {
            return;
        }
        if (beanFactory instanceof BeanDefinitionRegistry) {
            try {
                // init scanner
                Class<?> scannerClass =
                        ReflectUtils.forName("org.springframework.context.annotation" + "" + "" + "" +
                                ".ClassPathBeanDefinitionScanner");
                //获取ClassPathBeanDefinitionScanner 实例  获取类的含参私有构造函数，并实例化类
                //疑问点：为啥不直接new ClassPathBeanDefinitionScanner(),这种写法有什么独到之处？;
                Object scanner = scannerClass.getConstructor(new Class<?>[]{BeanDefinitionRegistry.class,
                        boolean.class}).newInstance(new Object[]{(BeanDefinitionRegistry) beanFactory, true});

                // add filter
                Class<?> filterClass = ReflectUtils.forName("org.springframework.core.type.filter" + "" + "" + "" +
                        "" + ".AnnotationTypeFilter");
                //获取AnnotationTypeFilter实例
                Object filter = filterClass.getConstructor(Class.class).newInstance(Service.class);
                //获取ClassPathBeanDefinitionScanner 里面的addIncludeFilter()方法
                Method addIncludeFilter = scannerClass.getMethod("addIncludeFilter",
                        ReflectUtils.forName("org" + "" + ".springframework.core.type.filter.TypeFilter"));
                //添加包下扫描类类型的过滤  然后把注解@service 添加到扫描过滤当中
                addIncludeFilter.invoke(scanner, filter);
                // scan packages
                String[] packages = Constants.COMMA_SPLIT_PATTERN.split(annotationPackage);
                //获取ClassPathBeanDefinitionScanner里面的scan方法
                Method scan = scannerClass.getMethod("scan", new Class<?>[]{String[].class});
                //通过反射调用扫描指定包下面的@service注解，将@Service 注解的类注册到ioc容器当中
                scan.invoke(scanner, new Object[]{packages});
            } catch (Throwable e) {
                // spring 2.0
            }
        }
    }

    public void destroy() throws Exception {
        for (ServiceConfig<?> serviceConfig : serviceConfigs) {
            try {
                serviceConfig.unexport();
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
            }
        }
        for (ReferenceConfig<?> referenceConfig : referenceConfigs.values()) {
            try {
                referenceConfig.destroy();
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /***
     * BeanPostProcessor接口作用是：
     * 如果我们需要在Spring容器完成Bean的实例化、配置和其他的初始化前后添加一些自己的逻辑处理，
     * 我们就可以定义一个或者多个BeanPostProcessor接口的实现，然后注册到容器中。
     * @param bean
     * @param beanName
     * @return
     * @throws BeansException
     */
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!isMatchPackage(bean)) {
            return bean;
        }
        //获取实例化后类注解为Service的类
        Service service = bean.getClass().getAnnotation(Service.class);
        if (service != null) {
            //给实例化后注解为Service的类添加监听服务
            //将注解为Service的类封装到ServiceBean对象中
            ServiceBean<Object> serviceConfig = new ServiceBean<Object>(service);

            if (void.class.equals(service.interfaceClass()) && "".equals(service.interfaceName())) {
                if (bean.getClass().getInterfaces().length > 0) {
                    serviceConfig.setInterface(bean.getClass().getInterfaces()[0]);
                } else {
                    throw new IllegalStateException("Failed to export remote service class " + bean.getClass().getName() + ", cause: The @Service undefined interfaceClass or interfaceName, and the " + "service class unimplemented any interfaces.");
                }
            }
            if (applicationContext != null) {
                // 将spring 上下文注入到ServiceBean中
                serviceConfig.setApplicationContext(applicationContext);

                if (service.registry() != null && service.registry().length > 0) {
                    List<RegistryConfig> registryConfigs = new ArrayList<RegistryConfig>();
                    for (String registryId : service.registry()) {
                        if (registryId != null && registryId.length() > 0) {
                            registryConfigs.add((RegistryConfig) applicationContext.getBean(registryId,
                                    RegistryConfig.class));
                        }
                    }
                    serviceConfig.setRegistries(registryConfigs);
                }
                //下面的操作均为获取注解@Service类的注解值，如果存在，则将其设置到ServiceBean 中
                if (service.provider() != null && service.provider().length() > 0) {
                    serviceConfig.setProvider((ProviderConfig) applicationContext.getBean(service.provider(),
                            ProviderConfig.class));
                }
                if (service.monitor() != null && service.monitor().length() > 0) {
                    serviceConfig.setMonitor((MonitorConfig) applicationContext.getBean(service.monitor(),
                            MonitorConfig.class));
                }
                if (service.application() != null && service.application().length() > 0) {
                    serviceConfig.setApplication((ApplicationConfig) applicationContext.getBean(service.application()
                            , ApplicationConfig.class));
                }
                if (service.module() != null && service.module().length() > 0) {
                    serviceConfig.setModule((ModuleConfig) applicationContext.getBean(service.module(),
                            ModuleConfig.class));
                }
                if (service.provider() != null && service.provider().length() > 0) {
                    serviceConfig.setProvider((ProviderConfig) applicationContext.getBean(service.provider(),
                            ProviderConfig.class));
                } else {

                }
                if (service.protocol() != null && service.protocol().length > 0) {
                    List<ProtocolConfig> protocolConfigs = new ArrayList<ProtocolConfig>();
                    for (String protocolId : service.registry()) {
                        if (protocolId != null && protocolId.length() > 0) {
                            protocolConfigs.add((ProtocolConfig) applicationContext.getBean(protocolId,
                                    ProtocolConfig.class));
                        }
                    }
                    serviceConfig.setProtocols(protocolConfigs);
                }
                try {
                    serviceConfig.afterPropertiesSet();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }
            serviceConfig.setRef(bean);
            serviceConfigs.add(serviceConfig);
            serviceConfig.export();
        }
        return bean;
    }

    /***
     * postProcessBeforeInitialization实现了对Reference的处理。处理分为两部分，一部分是对方法Reference的处理；一部分是对Field
     * 的Reference的处理。两部分的处理都是通过refer方法来处理的。

     而get方法中主要逻辑是:
     首先看这个对象是不是已经被destroyed(Reference注解的对象都是远程对象)。如果没有，那么看当前有没有现成的引用；如果没有就调用init()
     方法。init中除了设置ReferenceConfig对象的状态外，还会初始化一些参数checkAndLoadConfig。Dubbo将这部分参数都放入System
     .properties中，并根据Rerernce中设置的Value获取在服务器端的Key。之后会调用createProxy()
     .在该方法中，主要是生成对应的invoker，这是RPC调用的关键，记录包括类名，地址等关键信息。之后调用proxyFactory.getProxy(this.invoker)。
     proxyFactory是调用协议的工厂类，通过调用gerProxy方法找到对应的协议，类名以及地址，
     最后调用协议的this.protocol.export(this.proxyFactory.getInvoker(instance, type, url))。
     这样能够调用到远程的对象，并置换本地对应的Reference注解参数。
     * @param bean
     * @param beanName
     * @return
     * @throws BeansException
     */
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (!isMatchPackage(bean)) {
            return bean;
        }
        Method[] methods = bean.getClass().getMethods();
        for (Method method : methods) {
            String name = method.getName();
            //判断当前方法是否为set方法、参数个数为1、类型为public
            if (name.length() > 3 && name.startsWith("set") && method.getParameterTypes().length == 1 && Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) {
                try {
                    Reference reference = method.getAnnotation(Reference.class);
                    if (reference != null) {
                        Object value = refer(reference, method.getParameterTypes()[0]);
                        if (value != null) {
                            method.invoke(bean, new Object[]{});
                        }
                    }
                } catch (Throwable e) {
                    logger.error("Failed to init remote service reference at method " + name + " in class " + bean.getClass().getName() + ", cause: " + e.getMessage(), e);
                }
            }
        }
        Field[] fields = bean.getClass().getDeclaredFields();
        for (Field field : fields) {
            try {
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                Reference reference = field.getAnnotation(Reference.class);
                if (reference != null) {
                    Object value = refer(reference, field.getType());
                    if (value != null) {
                        field.set(bean, value);
                    }
                }
            } catch (Throwable e) {
                logger.error("Failed to init remote service reference at filed " + field.getName() + " in class " + bean.getClass().getName() + ", cause: " + e.getMessage(), e);
            }
        }
        return bean;
    }

    /****
     * refer的逻辑对Dubbo初始化非常重要，这里尝试详细分析一下：
     当扫描到Reference注解后，调用refer函数，首先Dubbo需要明确interfaceName，根据不同类型的referenceClass，Dubbo会根据规则生成interfaceName
     。之后尝试从当前的referenceConfigs里获取，如果能够获取到referenceConfig就调用对应的get方法(这个方法后续再介绍).
     如果获取不到就需要new一个对象，对new出来的对象，根据Reference注解配置的参数进行设置，然后put到referenConfigs这个Map中，之后还调用get方法。
     * @param reference
     * @param referenceClass
     * @return
     */
    private Object refer(Reference reference, Class<?> referenceClass) { //method.getParameterTypes()[0]
        String interfaceName;
        if (!"".equals(reference.interfaceName())) {
            interfaceName = reference.interfaceName();
        } else if (!void.class.equals(reference.interfaceClass())) {
            interfaceName = reference.interfaceClass().getName();
        } else if (referenceClass.isInterface()) {
            interfaceName = referenceClass.getName();
        } else {
            throw new IllegalStateException("The @Reference undefined interfaceClass or interfaceName, and the " +
                    "property type " + referenceClass.getName() + " is not a interface.");
        }
        String key = reference.group() + "/" + interfaceName + ":" + reference.version();
        ReferenceBean<?> referenceConfig = referenceConfigs.get(key);
        if (referenceConfig == null) {
            referenceConfig = new ReferenceBean<Object>(reference);
            if (void.class.equals(reference.interfaceClass()) && "".equals(reference.interfaceName()) && referenceClass.isInterface()) {
                referenceConfig.setInterface(referenceClass);
            }
            if (applicationContext != null) {
                referenceConfig.setApplicationContext(applicationContext);
                if (reference.registry() != null && reference.registry().length > 0) {
                    List<RegistryConfig> registryConfigs = new ArrayList<RegistryConfig>();
                    for (String registryId : reference.registry()) {
                        if (registryId != null && registryId.length() > 0) {
                            registryConfigs.add((RegistryConfig) applicationContext.getBean(registryId,
                                    RegistryConfig.class));
                        }
                    }
                    referenceConfig.setRegistries(registryConfigs);
                }
                if (reference.consumer() != null && reference.consumer().length() > 0) {
                    referenceConfig.setConsumer((ConsumerConfig) applicationContext.getBean(reference.consumer(),
                            ConsumerConfig.class));
                }
                if (reference.monitor() != null && reference.monitor().length() > 0) {
                    referenceConfig.setMonitor((MonitorConfig) applicationContext.getBean(reference.monitor(),
                            MonitorConfig.class));
                }
                if (reference.application() != null && reference.application().length() > 0) {
                    referenceConfig.setApplication((ApplicationConfig) applicationContext.getBean(reference.application(), ApplicationConfig.class));
                }
                if (reference.module() != null && reference.module().length() > 0) {
                    referenceConfig.setModule((ModuleConfig) applicationContext.getBean(reference.module(),
                            ModuleConfig.class));
                }
                if (reference.consumer() != null && reference.consumer().length() > 0) {
                    referenceConfig.setConsumer((ConsumerConfig) applicationContext.getBean(reference.consumer(),
                            ConsumerConfig.class));
                }
                try {
                    referenceConfig.afterPropertiesSet();
                } catch (RuntimeException e) {
                    throw (RuntimeException) e;
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }
            referenceConfigs.putIfAbsent(key, referenceConfig);
            referenceConfig = referenceConfigs.get(key);
        }
        return referenceConfig.get();
    }

    private boolean isMatchPackage(Object bean) {
        if (annotationPackages == null || annotationPackages.length == 0) {
            return true;
        }
        String beanClassName = bean.getClass().getName();
        for (String pkg : annotationPackages) {
            if (beanClassName.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

}
