# EasyExcel MySQL Demo

基于 Spring Boot、EasyExcel、MyBatis 的 Excel 导入导出演示项目。

## 项目结构

```text
src/main/java/com/huang/demo
├── DemoApplication.java
└── excel
    ├── config        # 导入导出批次、初始化数据量配置
    ├── controller    # HTTP 接口
    ├── listener      # EasyExcel 导入监听器
    ├── model         # Excel 行模型
    ├── repository    # MyBatis Mapper 接口
    └── service       # 业务编排和计时日志
```

MyBatis XML 位于：

```text
src/main/resources/mapper/StudentMapper.xml
```

## 环境变量

数据库密码不写入配置文件，启动前需要设置：

```bash
export MYSQL_PASSWORD='your_mysql_password'
```

## 数据库脚本

脚本目录：

```text
src/main/resources/db/mysql
├── create_database.sql
├── create_tables.sql
└── schema.sql
```

如果 SQL 控制台不能稳定执行多语句脚本，推荐先执行 `create_database.sql`，选中 `demo` schema 后再执行 `create_tables.sql`。

## 启动和测试

```bash
MYSQL_PASSWORD='your_mysql_password' /Users/dingli/Dependent/apache-maven-3.6.3/bin/mvn test
MYSQL_PASSWORD='your_mysql_password' /Users/dingli/Dependent/apache-maven-3.6.3/bin/mvn spring-boot:run
```

## 接口

```text
GET  /api/excel/count
GET  /api/excel/export
POST /api/excel/import      multipart file 字段名: file
POST /api/excel/seed/{count}
```
