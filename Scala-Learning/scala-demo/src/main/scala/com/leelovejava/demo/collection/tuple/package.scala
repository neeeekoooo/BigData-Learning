package com.leelovejava.demo.collection

/**
  * 1.元组定义
  * 与列表一样，与列表不同的是元组可以包含不同类型的元素。元组的值是通过将单个的值包含在圆括号中构成的。
  * 2.创建元组与取值
  * val  tuple = new Tuple（1） 可以使用new
  * val tuple2  = Tuple（1,2） 可以不使用new，也可以直接写成val tuple3 =（1,2,3）
  * 取值用”._XX” 可以获取元组中的值
  * 注意：tuple最多支持22个参数
  * 3.元组的遍历
  * tuple.productIterator得到迭代器，进而遍历
  * 4.swap,toString方法
  * 注意：swap元素翻转，只针对二元组
  */
package object tuple {

}
