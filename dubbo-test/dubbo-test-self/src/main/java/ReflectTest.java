public class ReflectTest {

    public static void main(String[] args) {
        Class<?> scannerClass = ReflectUtils.forName("org.springframework.context.annotation" + "" + "" + ""
                + ".ClassPathBeanDefinitionScanner");
        //获取ClassPathBeanDefinitionScanner 实例  获取类的含参私有构造函数，并实例化类
        /*Object scanner = scannerClass.getConstructor(new Class<?>[]{BeanDefinitionRegistry.class, boolean
                .class}).newInstance(new Object[]{(BeanDefinitionRegistry) beanFactory, true});*/
    }
}
