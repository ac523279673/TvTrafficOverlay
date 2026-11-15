# 第三方资源声明

本项目源码以 MIT 许可证发布，但下面这项资源**不遵循 MIT**，另有独立授权。

## MiSans 字体

- 文件：`app/src/main/assets/fonts/MiSans-Regular.ttf`、`MiSans-DemiBold.ttf`
- 来源：小米（Xiaomi）发布的 MiSans 字体，官方仓库 <https://github.com/hy-acheng/MiSans> / 小米官网 <https://hyperos.mi.com/font>
- 授权：MiSans 字体知识产权许可协议，**免费商用**，但要求：
  1. 不得对字体本身进行改编、修改或二次开发；
  2. 不得单独出售字体；
  3. 使用方需注明使用了 MiSans 字体。

### 关于子集化

仓库内的字体文件是**子集（subset）**：仅保留了 GB2312 全部汉字、拉丁字母、数字与常用符号，共 7544 个字符，用于把单字重从 7.6 MB 压到 1.7 MB。

子集化只裁剪字符集，**未改动任何字形轮廓、字重或间距**。如果你认为子集化属于原协议所限制的「改编」，可以在构建前删除这两个文件，改用小米官方分发的完整字体并重命名为相同文件名，功能不受影响。

本项目在 App 设置页底部与 README 中均已注明使用了 MiSans。
