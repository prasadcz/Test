import hudson.model.*
import hudson.plugins.ec2.UnixData
import jenkins.model.Jenkins
import groovy.json.JsonBuilder
import groovy.json.*
import groovy.json.JsonSlurperClassic
import groovy.json.JsonSlurper
import java.io.File
import groovy.json.JsonOutput
import java.lang.*
import java.util.regex.*
import java.net.URLEncoder


 def latestJsonObj
 def distro_name
 def env_family
 def app_version_timestamp
 def apps_release_bucket
 def apps_artifacts_bucketname
 def apps_artifacts_folder
 def terraform_artifacts_bucketname
 def apps_certs_bucket
 def need_apn_certs
 def dsc_app_folder_exists
 def dsc_app_folder
 def latestJsonEnv
 def latestJsonObjEnv
 def application_name
 def appdynamics_group_name
 def cloudwatch_uuid
 def AGENT_LABEL
 def staticEnv
 def config_bucket

// node('jenkins-linux-test') {
//   stage('Checkout and set agent'){
//      script{
//        def App_Name = "${params.applicationName}"
//        if (App_Name.startsWith("provision.css.mcafee.com") || App_Name.startsWith("provisionadmin.ccs.int.mcafee.com"))
//         //if (applicationname.matches("Jenkins_agent"))
//         //if (Stream.of("Jenkins_agent").anyMatch(applist "->" application_name.startsWith(applist)))
//        {
//          AGENT_LABEL = "CSP_NonProd_Jenkins_Agent_Group1"
//        }
//        else if (App_Name.startsWith("bridgeservice.int.mcafee.com"))
//        {
//          AGENT_LABEL = "CSP_NonProd_Jenkins_Agent_Group2"
//        }
//        else
//        {
//          AGENT_LABEL = "CSP_NonProd_Jenkins_Agent"
//        }
//      }
  
//    }
// }

pipeline 
{
    agent {
         // label 'CSP_NonProd_Jenkins_Agent'
         label "sharedservice-nonprod-agent"
         }

    tools 
    {
      terraform "Terraform0.14.9linux"
    }

   parameters
    {
       string(name: 'app_version', defaultValue: '1', description: 'Application Version')
       validatingString(name: "Env_Name", defaultValue: "dev", regex: /(dev\d+)|(dev)|(qa\d+)|(qa)|(cat\d+)|(cat)|(stage\d+)|(stage)|(prod\d+)|(prod)/, failedValidationMessage: "Validation failed!", description: "Environment Name")
       booleanParam(name: 'destroy', defaultValue: false, description: 'Do you want to destroy the infrastruture?')
    }
    
    environment 
    {
        environmentName = "${Env_Name}"
    }
    
stages 
{    
        stage('Cleanup workspace')
        {
            steps
            {       
                  echo "Cleanup workspace"
                  cleanWs()

            }
        }
    
        stage('Get the App jenkins pipeline configuration')
        {
            steps
            {
                script 
                {
                 
                config_bucket = "consumer-apps-release";
                sh label: '', script: "aws s3 cp s3://${config_bucket}/ConfigFiles/${applicationName}/config.json config.json"
                latestJsonObj = getEnvironmentDetails('config.json');
                application_name = latestJsonObj.get("applicationName")
                apps_release_bucket = latestJsonObj.get("apps_release_bucket");
                apps_artifacts_bucketname = latestJsonObj.get("apps_artifacts_bucketname");
                distro_name= latestJsonObj.get("distro_name");
                apps_artifacts_folder = latestJsonObj.get("apps_artifacts_folder");
                terraform_artifacts_bucketname = latestJsonObj.get("terraform_artifacts_bucketname");
                apps_certs_bucket = latestJsonObj.get("apps_certs_bucket_appfolder");
                need_apn_certs = latestJsonObj.get("need_apn_certs");
                dsc_app_folder_exists = latestJsonObj.get("dsc_app_folder_exists");
                dsc_app_folder = latestJsonObj.get("dsc_app_folder");
               
                if(environmentName.startsWith("dev"))
                    env_family = "dev"
                else if(environmentName.startsWith("cat"))
                    env_family = "cat"
                else if(environmentName.startsWith("qa"))
                    env_family = "qa"
                else if(environmentName.startsWith("stage"))
                    env_family = "stage"
                else if(environmentName.startsWith("prod"))
                    env_family = "prod"
                else 
                    error "${environmentName} is not valid!!!."
                 
                }
                
            }
        }

        stage('Get & Validate Environemnt Configuration')
        {
            steps{
                script{
                    if (environmentName.startsWith("dev") || environmentName.startsWith("qa") || environmentName.startsWith("cat"))
                    {
                        def existEnvjson = sh label: 'checkEnvJsonExist', returnStdout: true, script: "aws s3api list-objects --bucket \"${apps_release_bucket}\" --prefix  \"ConfigFiles/${applicationName}/\" --query \"sort_by(Contents,&LastModified)[?contains(Key, 'lower_environment.json')].{Key: Key, LastModified:LastModified}\" --output json"
                        println (existEnvjson)
                         def statusEnv = jsonArrayToArrayList(existEnvjson)
                          println(statusEnv.size())
                        if(statusEnv.size() == 0)
                            {
                                echo "Creating Environment Json file as it not present"
                                sh label: 'CreateEnvJson', script: "touch lower_environment.json"
                            }
                        if(statusEnv.size() > 0)
                            {
                                echo "Ennvironment Json file Exists"
                                sh label: 'GetEnvJsonfile', script: "aws s3 cp s3://${apps_release_bucket}/ConfigFiles/${applicationName}/lower_environment.json lower_environment.json"
                                latestJsonEnv = getEnvironmentDetails('lower_environment.json');
                                if(latestJsonEnv && latestJsonEnv.get(environmentName))
                                {
                                    if(environmentName != "cat" && environmentName != "cat10" && environmentName != "qa200" && environmentName != "qa210" && environmentName != "qa777") {
                                        input ("$environmentName is exist. Do you want to proceed?")
                                    }
                                }
                            }
                    }   
                }
            }
        }

    stage('Manage the host file')
        {
            when() {
                  environment name: 'destroy', value: "false"
            }
            steps{
                script{
                    if (environmentName.startsWith("dev") || environmentName.startsWith("qa") || environmentName.startsWith("cat"))
                    {
                        def existEnvjson = sh label: 'checkHostFileEntry', returnStdout: true, script: "aws s3api list-objects --bucket \"${apps_release_bucket}\" --prefix  \"hostentries/${env_family}/\" --query \"sort_by(Contents,&LastModified)[?contains(Key, 'hosts')].{Key: Key, LastModified:LastModified}\" --output json"
                        println (existEnvjson)
                         def statusEnv = jsonArrayToArrayList(existEnvjson)
                          println(statusEnv.size())
                        if(statusEnv.size() == 0)
                            {
                                try
                                {
                                
                                input 'Do you want to enter the host entries? (Abort will continue the next steps)'
                                def userInput = input(
                                    id: 'userInput', message: 'Do you want to give host entries?',
                                    parameters: [
                                            text(name:'Host_Entries', defaultValue:'', description:'Host File Entries for Dev/QA Env')
                                    ])

                                println("*************** HOST ENTRIES ***************************")
                                println(userInput)
                               
                                sh """ mkdir hostentries """
                                writeFile(file: "hosts", text: "${userInput}", encoding: "UTF-8")
                                withAWS(region:'us-west-2', role:"arn:aws:iam::406502849100:role/infra_deploy")
                                {
                                    s3Upload(bucket:"${apps_release_bucket}", path:"hostentries/${env_family}/", file:"hosts");
                                }

                                sh """ mv hosts hostentries """
                                }catch(exception) {
                                    println(exception)
                                }
                            }
                        if(statusEnv.size() > 0)
                            {
                                echo "Hosts file Exists"
                                sh label: 'Get Hosts file', script: "aws s3 cp s3://${apps_release_bucket}/hostentries/${env_family}/hosts hosts"
                                def data = readFile(file: 'hosts');
                                sh """ mkdir hostentries """
                                println("*************** HOST ENTRIES ***************************")
                                println(data);
                                if(environmentName != "cat" && environmentName != "cat10" && environmentName != "qa200" && environmentName != "qa210" && environmentName != "qa777") {
                                    try
                                    {
                                        input 'Do you want to update the host entries? (Abort will continue the next steps)'

                                        def userInput = input(
                                        id: 'userInput', message: 'Do you want to update the host entries?',
                                        parameters: [
                                                text(name:'Host_Entries', defaultValue:"${data}", description:'Host File Entries for Dev/QA Env')
                                        ])

                                        println("*************** HOST ENTRIES ***************************")
                                        println(userInput)
                                        writeFile(file: "hosts", text: "${userInput}", encoding: "UTF-8")

                                        // input 'Do you want to update the host entries in the S3?'
                                        // withAWS(region:'us-west-2',credentials:'AWS9100S3')
                                        // {
                                        // s3Upload(bucket:"${apps_release_bucket}/hostentries/${env_family}", file:"hosts");
                                        // }
                                        sh """ mv hosts hostentries """

                                    }catch(ex)
                                    {
                                        println(ex)
                                        sh """ mv hosts hostentries """
                                    }
                                }
                            }
                    }   
                }
            }
        }

    stage('Configure Cloud Watch Agent')
        {
            when() {
                  environment name: 'destroy', value: "false"
            }
            steps{
                script{
                    cloudwatch_uuid = new Date().getTime();  
                    sh label: 'GetCloudWatchTeamplate', script: "aws s3 cp s3://${apps_release_bucket}/cloudwatch/cloudwatch.json cloudwatch.json"
                    //create cloud watch folder
                    sh """ mkdir -p cloudwatch """
                    sh """ mv cloudwatch.json cloudwatch/ """
                    // powershell label: '', script: "(Get-Content ${WORKSPACE}\\cloudwatch\\cloudwatch.json).replace('#CLOUDWATCHNAME', '${environmentName}-${application_name}-${cloudwatch_uuid}') | Set-Content ${WORKSPACE}\\cloudwatch\\cloudwatch.json"  

                    sh """ sed -i 's/#CLOUDWATCHNAME/${environmentName}-${application_name}-${cloudwatch_uuid}/g' "\${WORKSPACE}/cloudwatch/cloudwatch.json" """
                }
            }
        }

        stage('S3 App Code Download') {
            when {
                environment name: 'destroy', value: "false"
            }
            steps {
                script {
                    try {
                        def s3Object = "${apps_artifacts_folder}/${distro_name}.${app_version}.zip"
                        echo "S3 OBJECT -- ${s3Object}"
                        withAWS(role: "arn:aws:iam::406502849100:role/infra_deploy") {
                            s3Download(file: "${distro_name}.${app_version}.zip", bucket: "${apps_artifacts_bucketname}", path: "${s3Object}")
                        }
                    } catch (Exception e) {
                        echo "ERROR: Failed to download from S3 - ${e.getMessage()}"
                        currentBuild.result = 'FAILURE'
                        error("S3 download failed")  // Fail the build explicitly
                    }
                }
            }
        }

        stage ('UNZIP S3 Artifact')
        {
             when() {
                  environment name: 'destroy', value: "false"
            }
            steps
            {
                echo "UNZIP - ${distro_name}.${app_version}.zip"
                //unzip zipFile: "${distro_name}.${app_version}.zip", dir:"", quiet:true
                // powershell label: 'UnzipFile', script: "Expand-Archive ${distro_name}.${app_version}.zip -DestinationPath ${WORKSPACE}\\"
                sh label: 'UnzipFile', script: "unzip  -q ${distro_name}.${app_version}.zip -d ${WORKSPACE}/"
                echo "END - UNZIP"
                echo "Delete ZIP file "
                // bat """ del ${distro_name}.${app_version}.zip"""
                sh """ rm -f ${distro_name}.${app_version}.zip"""
            
            }
        }

        stage('Replace app configuration with AWS Parameter Store values') 
        {
             when() {
                  environment name: 'destroy', value: "false"
            }
            steps 
            {
                echo 'Get Env Params from aws ParamStore'
                script
                {    
                    echo "${WORKSPACE}"
                    def aws_param_path= latestJsonObj.get("aws_param_path").replace("#ENVIRONMENT#",environmentName);
                    echo "AWS parameter path -- $aws_param_path"
                    def files_list= latestJsonObj.get("files")
                    deploymentParams = new HashMap<String, String>()
                    ArrayList deploymentParamsJson = new ArrayList()

                    if(files_list.size() > 0)
                    {
                        def paramStoreEnvResponseObj = sh label: 'GetParamStore', returnStdout: true, script: "aws ssm get-parameters-by-path --path '${aws_param_path}' --query \"Parameters[*].{key:Name,value:Value, secret:'true', LastModifiedDate:LastModifiedDate}\"  --with-decryption --region us-west-2"
                        
                        HashMap<String, String> paramStoreEnv = jsonStringToHashMapParams(paramStoreEnvResponseObj,aws_param_path)
                      
                        print(paramStoreEnv.isEmpty());
                        if(paramStoreEnv.isEmpty() && env_family!=environmentName)
                        {
                            if(environmentName != "cat" && environmentName != "cat10" && environmentName != "qa200" && environmentName != "qa210" && environmentName != "qa777") {
                                input "No parameter store entries for the ${environmentName} Do you want to use ${env_family} environment?"
                            }
                        
                        aws_param_path = latestJsonObj.get("aws_param_path").replace("#ENVIRONMENT#",env_family);
                        paramStoreEnvResponseObj = sh label: 'GetParamStore', returnStdout: true, script: "aws ssm get-parameters-by-path --path '${aws_param_path}' --query \"Parameters[*].{key:Name,value:Value, secret:'true', LastModifiedDate:LastModifiedDate}\"  --with-decryption --region us-west-2"
                        paramStoreEnv = jsonStringToHashMapParams(paramStoreEnvResponseObj,aws_param_path)
                        }
                        deploymentParamsJson.addAll(jsonArrayToArrayList(paramStoreEnvResponseObj))
                        if(paramStoreEnv != null)
                        {
                            deploymentParams.putAll(paramStoreEnv)
                        }
                        for(String file in files_list)
                        {
                            def configFilePath = "${WORKSPACE}/${distro_name}.${app_version}/${file}"
                            String basePath = "${WORKSPACE}/${distro_name}.${app_version}/" // Set your base path here
                            String inputPath = "${file}" // Input path (case-insensitive)
                            
                            String correctPath = findCorrectPath(basePath, inputPath)
                            if (correctPath) {
                                println("Correct path: $correctPath")
                            } else {
                                println("Path not found! $correctPath")
                            }
                            if (fileExists("${correctPath}"))
                            {
                                replaceFileContent(correctPath,deploymentParams)
                                //ReplaceParamConfig(deploymentParams, configFilePath)
                            }
                            else
                            {
                                echo "Files path: ${correctPath} doesn't exist."
                            }
                        }
                    }
                }        
            }
        }

    

   stage('DSC Scripts Download') 
        {
             when() {
                  environment name: 'destroy', value: "false"
            }
            steps {
                script {
                    if(dsc_app_folder_exists == true)
                    {
                        def s3Object = "dsc/${dsc_app_folder}/"
                        echo "S3 OBJECT -- ${s3Object}"
                        withAWS(role:"arn:aws:iam::406502849100:role/infra_deploy") {
                        //s3Download(bucket: '${apps_release_bucket}', path:"${s3Object}",force:true)
                        sh """ aws s3 cp s3://${apps_release_bucket}/${s3Object} ${s3Object} --recursive """
                        }
                    }                
                }
            }

        }

        stage('Getting the APN Certs from S3 ')
        {
        when() {
                  environment name: 'destroy', value: "false"
            }  
            steps
            {
                script
                {
                    if(need_apn_certs == true)
                    {
                        echo "Creating APN certificate folder"
                        sh """ mkdir apn-certificate """
                        echo " APN certificate folder created"
                        withAWS(region:'us-west-2',role:"arn:aws:iam::406502849100:role/infra_deploy")
                        {
                            //s3Download(bucket:"${apps_certs_bucket}", file:'apn-certificate',force:true);
                            sh """ aws s3 cp s3://${apps_certs_bucket} apn-certificate --recursive """
                        }
                    }
                }   
            }
        }

        stage ('Upload the application code zip to S3')
        {
             when() {
                  environment name: 'destroy', value: "false"
            }
            steps
            {
                script
                {   
                    echo "ZIP APP CODE"
                    def currenttimestamp = new Date().getTime();
                    app_version_timestamp = app_version +"_" + currenttimestamp;
                    // bat """ rename ${distro_name}.${app_version} ${application_name}"""
                    sh """ mv ${distro_name}.${app_version} ${application_name}"""
                    zip zipFile: "${application_name}.${app_version_timestamp}.zip", glob : "${application_name}/**, dsc/**, apn-certificate/**, hostentries/**, cloudwatch/**", quiet:true
                    echo "END - ZIP"
                    withAWS(region:'us-west-2',role:"arn:aws:iam::406502849100:role/infra_deploy")
                    {
                        s3Upload(bucket:"${apps_release_bucket}", path:"${application_name}/${environmentName}/Release/", file:"${application_name}.${app_version_timestamp}.zip");
                    }
                }
            }
        }

        stage('Getting the IaC code from S3 ')
        {
            
            steps
            {
                withAWS(region:'us-west-2',role:"arn:aws:iam::406502849100:role/infra_deploy")
                {
                    // s3Download(bucket:"${terraform_artifacts_bucketname}", file:'terraform_artifacts-pipeline.zip', path:'terraform_artifacts-pipeline.zip', force:true);
                    sh """ aws s3 cp s3://${terraform_artifacts_bucketname}/terraform_artifacts-pipeline.zip terraform_artifacts-pipeline.zip """
                }
                //unzip dir: 'iac-code', glob: '', zipFile: "terraform_artifacts-pipeline.zip", quiet:true
                // powershell label: 'UnzipFile', script: "Expand-Archive terraform_artifacts-pipeline.zip -DestinationPath ${WORKSPACE}\\iac-code\\"
                sh label: 'UnzipFile', script: "unzip -q terraform_artifacts-pipeline.zip -d ${WORKSPACE}/iac-code/"

            }
        }
    
        stage('Running IaC Code')
        {
             when() {
                  environment name: 'destroy', value: "false"
            }
            steps
            {
                script
                {
                    if(environmentName.toLowerCase() == "dummy")
                    {
                        terraform_code_app_folder = latestJsonObj.get("terraform_code_app_folder") + "/modules_cat"
                    }
                    else
                    {
                        terraform_code_app_folder = latestJsonObj.get("terraform_code_app_folder") + "/modules"
                    }
                }

                //powershell label: '', script: "(Get-Content ${WORKSPACE}\\iac-code\\$terraform_code_app_folder\\modules\\main.tf).replace('git@github.int.mcafee.com:mcafee', 'git::https://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@github.int.mcafee.com/mcafee') | Set-Content ${WORKSPACE}\\iac-code\\$terraform_code_app_folder\\modules\\main.tf"
                withAWS(region:'us-west-2',role:"arn:aws:iam::406502849100:role/infra_deploy") 
                {
                    // bat """
                    //     Rem cd  ${WORKSPACE}\\iac-code\\$terraform_code_app_folder\\modules
                    //     cd  ${WORKSPACE}\\iac-code\\$terraform_code_app_folder
                    //     terraform init
                    //     terraform workspace select $environmentName || terraform workspace new $environmentName
                    //     terraform init
                    //     terraform plan -var-file="${env_family}.tfvars" -var appVersion=${app_version_timestamp} -var envVersion=$environmentName -var hostFlag=true
                    //     """
                    sh """
                        cd ${WORKSPACE}/iac-code/$terraform_code_app_folder
                        terraform init
                        terraform workspace select $environmentName || terraform workspace new $environmentName
                        terraform init
                        terraform plan -var-file="${env_family}.tfvars" -var appVersion=${app_version_timestamp} -var envVersion=$environmentName -var hostFlag=true
                    """
                    script {
                        if(environmentName != "cat" && environmentName != "cat10" && environmentName != "qa200" && environmentName != "qa210" && environmentName != "qa777") {
                            input "Do you want to build infrastruture for ${environmentName}?"
                        }
                    }

                    // bat """
                    //     Rem cd  ${WORKSPACE}\\iac-code\\$terraform_code_app_folder\\modules
                    //     cd  ${WORKSPACE}\\iac-code\\$terraform_code_app_folder
                    //     terraform init
                    //     terraform workspace select $environmentName
                    //     terraform init
                    //     terraform apply -var-file="${env_family}.tfvars" -var appVersion=${app_version_timestamp} -var envVersion=$environmentName -var hostFlag=true --auto-approve
                    //     terraform output -json > ${WORKSPACE}\\iac-ouput.json
                    //     """
                    sh """
                        # Navigate to the specified directory
                        cd ${WORKSPACE}/iac-code/$terraform_code_app_folder
                        terraform workspace select $environmentName
                        terraform init
                        terraform apply -var-file="${env_family}.tfvars" -var appVersion=${app_version_timestamp} -var envVersion=$environmentName -var hostFlag=true --auto-approve
                        terraform output -json > ${WORKSPACE}/iac-ouput.json
                    """
                }

                script
                {
                    def output_list = readFile("${WORKSPACE}/iac-ouput.json")
                    echo "Displaying iac-ouput.json size"
                    def output_size = jsonParseText(output_list)
                    println(output_size.size())
                    if (output_size.size() > 0)
                    {
                        terraformOutput = getEnvironmentDetails("${WORKSPACE}/iac-ouput.json");
                        assumerole_arn = terraformOutput.get("assumerole_arn")?.get("value");
                        autoscaling_group_name = terraformOutput.get("autoscaling_group_name")?.get("value")?.get("autoscaling_group_name");
                        targetGroupARN = terraformOutput.get("network-lb")?.get("value")?.get("https_target_group_arns") ?: terraformOutput.get("network-nlb-external")?.get("value")?.get("https_target_group_arns")
                        route53_record_fqdn = terraformOutput.get("route53_record_fqdn").get("value").get("route53_record_fqdn");

                        print("DNS Record Name :: " + route53_record_fqdn);
                        // def nslookupVips = powershell label: 'PrintVIPs', script: "nslookup ${route53_record_fqdn} 2>\$null", returnStdout: true
                        if( application_name == "nexs" || application_name == "provcs" || application_name == "nexsgtwy" || application_name == "vzwam") {
                            route53_record_fqdn1 = terraformOutput.get("route53_record_fqdn").get("value").get("external_route53_record_fqdn");
                            route53_record_fqdn2 = terraformOutput.get("route53_record_fqdn").get("value").get("internal_route53_record_fqdn");
                            def nslookupVips1 = sh label: 'PrintVIPs-1', script: "nslookup ${route53_record_fqdn1} 2>/dev/null", returnStdout: true
                            println(nslookupVips1)
                            def nslookupVips2 = sh label: 'PrintVIPs-2', script: "nslookup ${route53_record_fqdn2} 2>/dev/null", returnStdout: true
                            println(nslookupVips2)
                        }
                        else {
                            def nslookupVips = sh label: 'PrintVIPs', script: "nslookup ${route53_record_fqdn} 2>/dev/null", returnStdout: true
                            println(nslookupVips)
                        }

                        // Common environments alb listener switch and remove static nlb
                        if( application_name != "pricewizard") {
                            if(environmentName == "cat" || environmentName == "cat10" || environmentName == "qa200" || environmentName == "qa210") {
                                withAWS(role:"${assumerole_arn}") {
                                    sh label:'Add suspend process' , returnStdout: true, script: """ aws autoscaling suspend-processes --auto-scaling-group-name ${autoscaling_group_name} --scaling-processes HealthCheck ReplaceUnhealthy AlarmNotification InstanceRefresh """
                                }
                                input 'Switch connection to new infra? (Abort will kill the pipeline)'
                                if(application_name == "provcs" ||  application_name == "psactv"){
                                    nlb_http_target_group_arns = terraformOutput.get("network-nlb-external")?.get("value")?.get("http_target_group_arns")
                                    nlb_https_target_group_arns = terraformOutput.get("network-nlb-external")?.get("value")?.get("https_target_group_arns")
                                    nlb_http_listener_arn  = terraformOutput.get("network-nlb-external")?.get("value")?.get("http_listener_arn")
                                    nlb_https_listener_arn = terraformOutput.get("network-nlb-external`")?.get("value")?.get("https_listener_arn")
                                }
                                else{
                                    nlb_http_target_group_arns = terraformOutput.get("network-lb")?.get("value")?.get("http_target_group_arns")
                                    nlb_https_target_group_arns = terraformOutput.get("network-lb")?.get("value")?.get("https_target_group_arns")
                                    nlb_http_listener_arn  = terraformOutput.get("network-lb")?.get("value")?.get("http_listener_arn")
                                    nlb_https_listener_arn = terraformOutput.get("network-lb")?.get("value")?.get("https_listener_arn")
                                }
                                if(application_name == "consroute"){
                                    updateLoadBalancers(assumerole_arn, nlb_http_target_group_arns, nlb_https_target_group_arns, nlb_http_listener_arn, nlb_https_listener_arn, "consrout", environmentName, autoscaling_group_name)
                                }
                                else{
                                    updateLoadBalancers(assumerole_arn, nlb_http_target_group_arns, nlb_https_target_group_arns, nlb_http_listener_arn, nlb_https_listener_arn, application_name, environmentName, autoscaling_group_name)
                                }

                                // Remove static NLB which is not in use
                                if( application_name != "nexsgtwy" ||  application_name != "nexs" ||  application_name != "provcs") {
                                    try {
                                    withAWS(role:"${assumerole_arn}") {
                                        offline_nlb_arn = terraformOutput.get("network-lb")?.get("value")?.get("lb_id") ?: terraformOutput.get("network-lb")?.get("value")?.get("network-lb_id")
                                        def removeOfflineNlb = sh label:'Remove Offline Nlb' , returnStdout: true, script: """ aws elbv2 delete-load-balancer --load-balancer-arn ${offline_nlb_arn} """
                                        println("Removed offline NLB ${offline_nlb_arn}")
                                    }
                                    }
                                    catch(err) {
                                        println("Error in deleting NLB please check the error: ${err}")
                                    }
                                }
                            }
                        }

                        if(autoscaling_group_name != null && targetGroupARN != null){
                            withAWS(role:"${assumerole_arn}") 
                            {
                                def jenkinsUserDetails = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
                                println("Executed by user $jenkinsUserDetails.userName")
                                echo "jenkinsUserDetails: $jenkinsUserDetails.userName"

                                def instanceList = sh label:'EC2DescribeInstance' , returnStdout: true, script: """ aws ec2 describe-instances --filters 'Name=tag:aws:autoscaling:groupName,Values=${autoscaling_group_name}' 'Name=instance-state-name,Values=running' --query 'Reservations[].Instances[].{InstanceId:InstanceId}' """
                                println(instanceList)
                                def instanceListJsonParse = jsonParseText(instanceList)
                                def instancesize = instanceListJsonParse.size()
                                echo "No of instances - ${instancesize}"
                                for (i = 0; i < instancesize; i++)
                                {
                                    def instanceIdList = instanceListJsonParse.get(i);
                                    echo "Displaying Instance Details : ${instanceIdList}"
                                    def instanceId = instanceIdList.get("InstanceId");
                                    println(instanceId)
                                    def addUserTag = sh label:'AddingUserTag' , returnStdout: true, script: """ aws ec2 create-tags --resources "${instanceId}" --tags Key=jenkinsUser,Value="'${jenkinsUserDetails.userName}'" """
                                    println("addUserTag: ${addUserTag}")

                                    // Update host file for common env
                                    if(environmentName == "cat" || environmentName == "cat10" ) {
                                        def updateHostFile = sh label:'updateHostFile' , returnStdout: true, script: """ aws ssm send-command --instance-ids "${instanceId}" --document-name "AWS-RunPowerShellScript" --parameters commands=["aws s3 cp s3://${config_bucket}/DevOps/HostFiles/hosts_db5/hosts C:/Windows/System32/drivers/etc/hosts --force"] """

                                        if(application_name == "provcs") {
                                            sh label:'copy jwt file', returnStdout: true, script: """ aws ssm send-command --instance-ids "${instanceId}" --document-name "AWS-RunPowerShellScript" --parameters commands=["aws s3 cp s3://${config_bucket}/DevOps/provision_pilot_DB5/publickeys.jwks D:/inetpub/provisionccs.mcafee.com-distribution/V2/App_Data/jwt --force"] """
                                        }
                                    }
                                    if(environmentName == "qa200" || environmentName == "qa210") {
                                        def updateHostFile = sh label:'updateHostFile' , returnStdout: true, script: """ aws ssm send-command --instance-ids "${instanceId}" --document-name "AWS-RunPowerShellScript" --parameters commands=["aws s3 cp s3://${config_bucket}/DevOps/HostFiles/hosts_db2/hosts C:/Windows/System32/drivers/etc/hosts --force"] """

                                        if(application_name == "provcs") {
                                            sh label:'copy jwt file', returnStdout: true, script: """ aws ssm send-command --instance-ids "${instanceId}" --document-name "AWS-RunPowerShellScript" --parameters commands=["aws s3 cp s3://${config_bucket}/DevOps/provision_ppqa1_DB2/publickeys.jwks  D:/inetpub/provisionccs.mcafee.com-distribution/V2/App_Data/jwt --force"] """
                                        }
                                    }

                                    // ADD OU Group except common environments
                                    if(environmentName != "cat" && environmentName != "cat10" && environmentName != "qa200" && environmentName != "qa210") {

                                        echo "Waiting for instance to setup"
                                        sleep 450
                                        println("Checking target group status for $instanceId")
                                        timeout(30){
                                            waitUntil(initialRecurrencePeriod: 15000) {
                                                def describeTargetgroup = sh label:'DescribeTargetGroup' , returnStdout: true, script: """ aws elbv2 describe-target-health --target-group-arn "${targetGroupARN}" """
                                                instanceStatus = jsonParseText(describeTargetgroup).get("TargetHealthDescriptions").get(0).get("TargetHealth").get("State")
                                                println("Checking instanceStatus: ${instanceStatus}")
                                                return(instanceStatus == "healthy")
                                            }
                                        }
                                        def addGroup = false;
                                        def addGroupCount = 0;
                                        while (!addGroup)
                                        {
                                            try {
                                                def ouGroupAdd = sh label:'AddGroup' , returnStdout: true, script: """ aws ssm send-command --instance-ids "${instanceId}" --document-name "AWS-RunPowerShellScript" --parameters commands=["Add-LocalGroupMember -Group "Administrators" -Member "McAfee.int\\\\world.gs.cons.engmcafeeint" -Verbose"] """
                                                println("Added ou group to the instance ");
                                                addGroup = true;
                                            } 
                                            catch (err) {
                                                println(err)
                                                println("Add ou group to the instance failed and trying again after 30 sec. ");
                                                sleep 30
                                                // Try for 10 times and exit the while loop
                                                addGroupCount++;
                                                if(addGroupCount == 10) {
                                                    error("Instance is not in valid state aborting the pipeline. Please re-run the pipeline")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    println (latestJsonEnv)
                    if (latestJsonEnv)
                    {
                        def updatedcurrentjson = '{"buildversion": '+app_version_timestamp+', "r53":""}';

                        def jsonObj = readJSON text: updatedcurrentjson;
                        latestJsonEnv.put(environmentName,jsonObj) 
                        SaveAndUploadLatestJson(latestJsonEnv,"lower_environment.json",latestJsonObj) 
                    }
                    else 
                    {
                        def updatedcurrentjson = ["$environmentName": ['buildversion': "${app_version_timestamp}" , 'r53':""]]    
                        SaveAndUploadLatestJson(updatedcurrentjson,"lower_environment.json",latestJsonObj) 
                    }

                    println (latestJsonEnv)
                }
            }
        }


        stage('Destroying the infrastruture')
        {
             when() {
                  environment name: 'destroy', value: "true"
            }
            steps
            {                
                script
               {
                    destoryInfra(environmentName,latestJsonObj,env_family)
                    println (latestJsonEnv)
                    if (latestJsonEnv)
                    { 
                        latestJsonEnv.remove(environmentName)  
                        println(latestJsonEnv)
                        SaveAndUploadLatestJson(latestJsonEnv,"lower_environment.json",latestJsonObj)
                    }
                }
            }
        }
    } //Stages End
    post {

     always {

         echo "cleaning the jenkins workspace"
         //deleteDir()
         cleanWs()
        }
    }
} //Pipeline End

@NonCPS
def getEnvironmentDetails(jsonFile)
{
    try
    {
        echo jsonFile
        def fileContent = readJSON file: "${jsonFile}"
        def json = JsonOutput.toJson(fileContent)
        def jsonstr = JsonOutput.prettyPrint(json)
        return new HashMap<>(jsonParseText(jsonstr));
    }
    catch (Exception e)
    {
        echo "Handle the exception in jsonStringToHashMapParams! ::: ${e}"
        error "Exiting the pipeline"
    }
}

@NonCPS
def jsonStringToHashMapParams(String fileObj,String replaceparam)
{   
    HashMap<String, String> paramMap = new HashMap<String, String>()
    try
    {  
        def variableList = jsonParseText(fileObj)
        for(def item : variableList) {            
            def paramKeyFull = item.get("key")
            def paramKey = paramKeyFull.replaceAll(replaceparam,'')
            def paramvalue = item.get("value")
            paramMap.put(paramKey, paramvalue);
        }
    }
    catch (Exception e) {
      echo "Handle the exception in jsonStringToHashMapParams! ::: ${e}"
    }
    return paramMap    
}

@NonCPS
def jsonParseText(String json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
}

@NonCPS
def jsonArrayToArrayList(jsonFile){
        return new ArrayList(jsonParseText(jsonFile))
}

def SaveAndUploadLatestJson( latestJsonEnv, latestJsonFile, latestJsonObj)
{    
    def release_bucket = latestJsonObj.get("apps_release_bucket");
    /*Save latest.json in workspace */
    writeJSON file: "${latestJsonFile}", json: latestJsonEnv //, pretty: 4 
    /* Upload updated latest.json in s3 bucket */
    sh """                    
        aws s3 cp ${latestJsonFile} s3://${release_bucket}/ConfigFiles/${applicationName}/${latestJsonFile}
        
    """
    println("The ${latestJsonFile} is uploaded successfully")
}

def replaceFileContent(configFilePath,HashMap<String, String> deploymentParams)
{
    def filePath = "${configFilePath}";
    echo "AWS Params replace File Path: ${filePath}"
    def content = readFile "${filePath}"
    for (String key : deploymentParams.keySet()) {
            
            String variableName = "\\\$\\{" + key + "\\}";
            String value = deploymentParams.get(key).replaceAll(Pattern.quote("\\"), Matcher.quoteReplacement("\\\\")).replaceAll(Pattern.quote("\$"), Matcher.quoteReplacement("\\\$"));
            Matcher matcher = Pattern.compile(variableName,2).matcher(content);
            int count = 0;
            while(matcher.find())
             {
                 count++;
             }
             //println("***** COUNTER***** " + count);
            content = matcher.replaceAll(value);
            println("replace times: " + count + ",  " + key + " => [******]");
        }
    writeFile(file: "${filePath}", text: content, encoding: "UTF-8")
}

def destoryInfra(envtodelete,latestJsonObj,env_family)
{  
    if(environmentName != "cat" && environmentName != "cat10" && environmentName != "qa200" && environmentName != "qa210" && environmentName != "qa777") {
        input "Do you want to destroy the infrastructure for ${envtodelete}?"
    }  
    // def terraform_folder = latestJsonObj.get("terraform_code_app_folder")
    def tfvars = env_family + ".tfvars"

    if(environmentName.toLowerCase() == "dummy")
    {
        terraform_folder = latestJsonObj.get("terraform_code_app_folder") + "/modules_cat"
    }
    else
    {
        terraform_folder = latestJsonObj.get("terraform_code_app_folder") + "/modules"
    }
                
    //powershell label: '', script: "(Get-Content ${WORKSPACE}\\iac-code\\$terraform_folder\\modules\\main.tf).replace('git@github.int.mcafee.com:mcafee', 'git::https://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@github.int.mcafee.com/mcafee') | Set-Content ${WORKSPACE}\\iac-code\\$terraform_folder\\modules\\main.tf"
    try
    {
        withAWS(region:'us-west-2',role:"arn:aws:iam::406502849100:role/infra_deploy") 
        {
            // bat """
            //     Rem cd  ${WORKSPACE}\\iac-code\\$terraform_folder\\modules
            //     cd  ${WORKSPACE}\\iac-code\\$terraform_folder
            //     terraform init
            //     terraform workspace select $envtodelete
            //     terraform refresh -var-file="$tfvars" -var appVersion=null -var envVersion=$envtodelete 
            //     terraform destroy -var-file="$tfvars" -var appVersion=null -var envVersion=$envtodelete --auto-approve
            //     """
            sh """
                # Navigate to the specified Terraform folder
                cd ${WORKSPACE}/iac-code/$terraform_folder
                terraform init
                terraform workspace select $envtodelete
                terraform refresh -var-file="$tfvars" -var appVersion=null -var envVersion=$envtodelete
                echo "==== Terraform Destroy Plan for ${envtodelete} ===="
                terraform plan -destroy -var-file="$tfvars" -var appVersion=null -var envVersion=$envtodelete
                """
                input "Do you really want to destroy the Infra for $envtodelete ?"
            sh """
                cd ${WORKSPACE}/iac-code/$terraform_folder
                terraform init
                terraform workspace select $envtodelete
                terraform refresh -var-file="$tfvars" -var appVersion=null -var envVersion=$envtodelete
                terraform destroy -var-file="$tfvars" -var appVersion=null -var envVersion=$envtodelete --auto-approve
                """
        }
    }
    catch(error)
    {
        echo "Destroy Infrastructure didn't pass.- Error in Run Command"
        println(error);
        if(environmentName != "cat" && environmentName != "cat10" && environmentName != "qa200" && environmentName != "qa210" && environmentName != "qa777") {
            input(message: 'Do you want to destroy the infrastructure again')
        }
        destoryInfra(envtodelete,latestJsonObj,env_family)
        return false;
    }
}

def updateLoadBalancers(assumerole_arn,nlb_http_target_group_arns, nlb_https_target_group_arns, nlb_http_listener_arn, nlb_https_listener_arn, application_name, environmentName, autoscaling_group_name) {
    withAWS(role:"${assumerole_arn}") {

        try {
            println("inside if loop and try block and environmentName : ${environmentName}")
            if(environmentName == "cat" || environmentName == "cat10") {
                staticEnv = "cat"
            }
            if(environmentName == "qa200" || environmentName == "qa210") {
                staticEnv = "qa200"
            }
            sh label:'Delete listener 1', returnStdout: true, script: """ aws elbv2 delete-listener --listener-arn ${nlb_http_listener_arn} """
            sh label:'Delete listener 2', returnStdout: true, script: """ aws elbv2 delete-listener --listener-arn ${nlb_https_listener_arn} """

            def staticNlbArn = sh label:'Fetch Static NlbArn' , returnStdout: true, script: """ aws elbv2 describe-load-balancers --names ${staticEnv}-nlb-${application_name} --query 'LoadBalancers[].LoadBalancerArn' --output text """
            staticNlbArn = staticNlbArn.replaceAll("\\s","")
            def listenersList = sh label:'Fetch Static Nlb Listner Arn' , returnStdout: true, script: """ aws elbv2 describe-listeners --load-balancer-arn ${staticNlbArn} --query 'Listeners[].ListenerArn' """
            listenersArnList = jsonParseText(listenersList)
            sh label:'Delete listener 1', script: """ aws elbv2 delete-listener --listener-arn ${listenersArnList.get(0)} """
            sh label:'Delete listener 2', script: """ aws elbv2 delete-listener --listener-arn ${listenersArnList.get(1)} """
            
            sh label:'Add listener 1', script: """ aws elbv2 create-listener --load-balancer-arn ${staticNlbArn} --protocol TCP --port 80 --default-actions Type=forward,TargetGroupArn=${nlb_http_target_group_arns} """
            sh label:'Add listener 2', script: """ aws elbv2 create-listener --load-balancer-arn ${staticNlbArn} --protocol TCP --port 443 --default-actions Type=forward,TargetGroupArn=${nlb_https_target_group_arns} """
            // Added suspend process for ASG
            sh label:'Add listener 2', script: """ aws autoscaling suspend-processes --auto-scaling-group-name ${autoscaling_group_name} --scaling-processes HealthCheck ReplaceUnhealthy AlarmNotification InstanceRefresh """
        }
        catch(err) {
            println(err)
        }
    }
}

/*
@NonCPS
def getConfigReplaceVariableList(HashMap<String, String> deploymentParams){
    ArrayList variableReplaceItemConfigList = new ArrayList()
    for(String key:deploymentParams.keySet()){
       // print("replace escape :: " + key + " == " +  deploymentParams.get(key))
        def item = variablesReplaceItemConfig( 
                    name: ""+key,
                    value: deploymentParams.get(key).replaceAll(Pattern.quote("\\"), Matcher.quoteReplacement("\\\\")).replaceAll(Pattern.quote("\$"), Matcher.quoteReplacement("\\\$")),
                )
        variableReplaceItemConfigList.add(item)
    }
    return variableReplaceItemConfigList
}    
@NonCPS
def ReplaceParamConfig(deploymentParams, configFilePath){
    println(configFilePath);

    return contentReplace(configs: [variablesReplaceConfig(configs: getConfigReplaceVariableList(deploymentParams), emptyValue: '', fileEncoding: 'UTF-8', filePath: "${configFilePath}", variablesPrefix: '${', variablesSuffix: '}')])
}
*/

def findCorrectPath(String basePath, String inputPath) {
    return sh(
        script: """
            #!/bin/bash
            base_path='$basePath' # Use single quotes to avoid Groovy interpolation
            input_path='$inputPath' # Use single quotes to avoid Groovy interpolation
 
            # Function to find the correct path
            find_correct_path() {
                local current_dir="\${base_path}"
                local IFS='/'
                read -ra components <<< "\${input_path}"
 
                for component in "\${components[@]}"; do
                    # Check case-insensitive match for the component
                    match=\$(find "\${current_dir}" -maxdepth 1 -iname "\${component}" | head -n 1)
                    if [ -z "\${match}" ]; then
                        echo "\${base_path}\${input_path}"
                        return 0
                    fi
                    current_dir="\${match}"
                done
 
                echo "\${current_dir}"
                return 0
            }
 
            # Call the function
            find_correct_path
        """,
        returnStdout: true
    ).trim()
}
