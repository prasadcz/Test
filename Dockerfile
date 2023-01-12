FROM openjdk:8
EXPOSE 3000
ADD springboot-welcome.jar springboot-welcome.jar
ENTRYPOINT ["java","-jar","/springboot-welcome.jar"]
