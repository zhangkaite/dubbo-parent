package com.alibaba.dubbo.demo.provider;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewMethod;
import javassist.Modifier;

public class CompilerByJavassist {

    public static void main(String[] args) throws Exception {
        // ClassPool: CtClass对象容器
        ClassPool pool = ClassPool.getDefault();

        // 通过ClassPool生成一个public的User类
        CtClass ctClass = pool.makeClass("com.kate.learn.javassist.User");


        // 添加属性
        // 1. 添加属性private int id;
        CtField idField = new CtField(pool.getCtClass("int"), "id", ctClass);
        idField.setModifiers(Modifier.PRIVATE);
        ctClass.addField(idField);

        // 2.添加属性private String username
        CtField nameField = new CtField(pool.get("java.lang.String"), "username", ctClass);
        nameField.setModifiers(Modifier.PRIVATE);
        ctClass.addField(nameField);

        // 添加setter/getter方法
        ctClass.addMethod(CtNewMethod.getter("getId", idField));
        ctClass.addMethod(CtNewMethod.setter("setId", idField));
        ctClass.addMethod(CtNewMethod.getter("getUsername", nameField));
        ctClass.addMethod(CtNewMethod.setter("setUsername", nameField));

        // 添加构造函数
        CtConstructor ctConstructor = new CtConstructor(new CtClass[]{}, ctClass);
        // 添加构造函数方法体
        StringBuffer sb = new StringBuffer();
        sb.append("{\n").append("this.id = 27;\n").append("this.username=\"carl\";\n}");
        ctConstructor.setBody(sb.toString());
        ctClass.addConstructor(ctConstructor);

        // 添加自定义方法
        CtMethod printMethod = new CtMethod(CtClass.voidType, "print", new CtClass[]{}, ctClass);
        printMethod.setModifiers(Modifier.PUBLIC);
        StringBuffer printSb = new StringBuffer();
        printSb.append("{\nSystem.out.println(\"begin!\");\n")
                .append("System.out.println(id);\n")
                .append("System.out.println(username);\n")
                .append("System.out.println(\"end!\");\n")
                .append("}");
        printMethod.setBody(printSb.toString());
        ctClass.addMethod(printMethod);

        // 生成一个Class
        Class<?> clazz = ctClass.toClass();
        Object obj = clazz.newInstance();

        // 反射执行方法
        obj.getClass().getMethod("print", new Class[]{}).invoke(obj, new Object[]{});

        // 把生成的class写入到文件中
/*      byte[] byteArr = ctClass.toBytecode();
        FileOutputStream fos = new FileOutputStream(new File("/kate/project"));
        fos.write(byteArr);
        fos.close();*/

    }
}
