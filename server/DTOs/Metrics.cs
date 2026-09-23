using System;
namespace client.DTOs;
public readonly record struct CPU (
    float Value,
    float Temp
);
