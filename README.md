HprofAnalysis
============

A Tool for Analyzing Hprof.

Use
--------
__1、Download__

* Step 1. download [jar](https://github.com/qq542391099/HprofAnalysis/raw/master/jar/HprofAnalysis.jar)

* Step 2. run cmd

java -jar HprofAnalysis.jar [hprof path]

java -jar HprofAnalysis.jar [hprof path] findLeak

java -jar HprofAnalysis.jar [hprof path] findLeak findBitmap

__2、Explain__

        _[hprof path] : The Hprof file path you need to analyze

        findLeak    : Just find leaked Activity and Fragment

        findBitmap  : find Bitmap address and its leakTrace_

Thanks
--------

  [LeakCanary](https://github.com/square/leakcanary)
  
 License
 -------
    Copyright [2018] [yph]
 
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at
 
      http://www.apache.org/licenses/LICENSE-2.0
 
    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
 
