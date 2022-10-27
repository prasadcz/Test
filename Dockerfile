FROM openjdk:8
EXPOSE 8080
ADD target/cz-springboot-welcome.jar cz-springboot-welcome.jar
ENTRYPOINT ["java","-jar","/cz-springboot-welcome.jar"]
